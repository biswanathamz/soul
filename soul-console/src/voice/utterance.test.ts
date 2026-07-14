import { describe, expect, it } from 'vitest';
import { UtteranceDetector } from './utterance';

const RATE = 16000;
const FRAME = 320; // 20 ms
const speech = () => new Float32Array(FRAME).fill(0.3);
const silence = () => new Float32Array(FRAME);

const make = () =>
  new UtteranceDetector({ sampleRate: RATE, endSilenceMs: 100, maxMs: 1000, preRollMs: 40 });

describe('UtteranceDetector', () => {
  it('waits through silence without ever finishing', () => {
    const d = make();
    for (let i = 0; i < 200; i++) expect(d.feed(silence()).state).toBe('waiting');
  });

  it('starts on speech and finishes after trailing silence, keeping pre-roll', () => {
    const d = make();
    d.feed(silence()); // becomes pre-roll
    expect(d.feed(speech()).state).toBe('recording');
    d.feed(speech());
    // 100 ms of silence = 5 frames of 20 ms
    let status = d.feed(silence());
    for (let i = 0; i < 10 && status.state !== 'done'; i++) status = d.feed(silence());
    expect(status.state).toBe('done');
    if (status.state === 'done') {
      // pre-roll (≤40ms) + 2 speech frames + trailing silence frames
      expect(status.audio.length).toBeGreaterThanOrEqual(FRAME * 3);
      // the speech made it in
      expect(Math.max(...status.audio)).toBeCloseTo(0.3);
    }
  });

  it('hard-caps a never-ending utterance at maxMs', () => {
    const d = make();
    let doneAt = -1;
    for (let i = 0; i < 100; i++) {
      if (d.feed(speech()).state === 'done') {
        doneAt = i;
        break;
      }
    }
    // 1000 ms cap at 20 ms frames → done by frame ~50
    expect(doneAt).toBeGreaterThan(0);
    expect(doneAt).toBeLessThanOrEqual(51);
  });

  it('brief blips below the adaptive gate do not trigger recording', () => {
    const d = make();
    const blip = new Float32Array(FRAME).fill(0.005); // becomes the noise floor → gate ≈ 0.0175
    for (let i = 0; i < 50; i++) expect(d.feed(blip).state).toBe('waiting');
  });

  it('adapts to quiet mics: soft speech over a silent floor still triggers', () => {
    const d = make();
    for (let i = 0; i < 20; i++) d.feed(silence()); // floor → ~0, gate falls to the minimum
    const soft = new Float32Array(FRAME).fill(0.01); // would NOT pass a fixed 0.015 gate
    expect(d.feed(soft).state).toBe('recording');
  });

  it('adapts to noisy rooms: the gate rises above the ambient floor', () => {
    const d = make();
    const noise = new Float32Array(FRAME).fill(0.02); // constant loud-ish ambience
    for (let i = 0; i < 30; i++) d.feed(noise);
    expect(d.gate()).toBeGreaterThan(0.02); // ambience alone can no longer trigger
    const speech = new Float32Array(FRAME).fill(0.2);
    expect(d.feed(speech).state).toBe('recording'); // real speech still does
  });
});
