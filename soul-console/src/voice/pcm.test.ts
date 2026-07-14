import { describe, expect, it } from 'vitest';
import { downsample, pcmToWavBytes, rmsOf } from './pcm';

describe('pcm helpers', () => {
  it('downsamples 48k → 16k by 3:1 averaging', () => {
    const input = new Float32Array([0.3, 0.3, 0.3, 0.9, 0.9, 0.9]);
    const out = downsample(input, 48000, 16000);
    expect(out.length).toBe(2);
    expect(out[0]).toBeCloseTo(0.3);
    expect(out[1]).toBeCloseTo(0.9);
  });

  it('passes audio through when rates match', () => {
    const input = new Float32Array([0.1, 0.2]);
    expect(downsample(input, 16000, 16000)).toBe(input);
  });

  it('rmsOf: silence is 0, a constant signal is its amplitude', () => {
    expect(rmsOf(new Float32Array(100))).toBe(0);
    expect(rmsOf(new Float32Array(100).fill(0.5))).toBeCloseTo(0.5);
  });

  it('encodes a valid 16-bit mono WAV header', () => {
    const pcm = new Float32Array([0, 0.5, -0.5, 1]);
    const v = new DataView(pcmToWavBytes(pcm, 16000));
    const tag = (off: number, n: number) =>
      Array.from({ length: n }, (_, i) => String.fromCharCode(v.getUint8(off + i))).join('');
    expect(tag(0, 4)).toBe('RIFF');
    expect(tag(8, 4)).toBe('WAVE');
    expect(v.getUint16(22, true)).toBe(1); // mono
    expect(v.getUint32(24, true)).toBe(16000); // sample rate
    expect(v.getUint32(40, true)).toBe(pcm.length * 2); // data bytes
    expect(v.getInt16(44, true)).toBe(0);
    expect(v.getInt16(46, true)).toBe(Math.floor(0.5 * 0x7fff)); // DataView truncates
    expect(v.getInt16(50, true)).toBe(0x7fff); // clamped full-scale
  });
});
