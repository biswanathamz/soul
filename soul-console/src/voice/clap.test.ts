import { describe, expect, it } from 'vitest';
import { ClapDetector } from './clap';

const RATE = 16000;
const FRAME = 1365; // ≈85 ms, like the real capture

/** A clap: mostly silence with a short, loud burst — high peak, low frame RMS. */
function clap(): Float32Array {
  const f = new Float32Array(FRAME);
  for (let i = 200; i < 300; i++) f[i] = 0.6;
  return f;
}
const silence = () => new Float32Array(FRAME);
const speech = () => new Float32Array(FRAME).fill(0.3); // loud but sustained, low crest

const make = () => new ClapDetector({ sampleRate: RATE, maxGapMs: 1500 });

describe('ClapDetector', () => {
  it('fires when the third clap is confirmed (spike + fall-back)', () => {
    const d = make();
    const fired = [clap(), silence(), clap(), silence(), clap(), silence()].map((f) => d.feed(f));
    expect(fired).toEqual([false, false, false, false, false, true]);
  });

  it('resets when claps are too far apart', () => {
    const d = make();
    for (const f of [clap(), silence(), clap(), silence()]) d.feed(f); // 2 claps counted
    for (let i = 0; i < 20; i++) d.feed(silence()); // > maxGap — count resets
    expect(d.feed(clap())).toBe(false);
    expect(d.feed(silence())).toBe(false); // this confirms clap #1, not #3
  });

  it('sustained speech never counts as claps', () => {
    const d = make();
    for (let i = 0; i < 30; i++) expect(d.feed(speech())).toBe(false);
    expect(d.feed(silence())).toBe(false); // even the fall after speech is not a clap
  });

  it('a clap spilling into a second frame still counts once', () => {
    const d = make();
    const seq = [clap(), clap(), silence(), clap(), silence(), clap(), silence()];
    const fired = seq.map((f) => d.feed(f));
    expect(fired).toEqual([false, false, false, false, false, false, true]);
  });

  it('counts claps with a reverb/AGC tail that never reaches silence (real rooms)', () => {
    const d = make();
    // The user-reported case: spike ~0.13, tail holds ~0.03 — far above the
    // noise floor, but well under 30% of the spike. Must count, not discard.
    const spike = () => {
      const f = new Float32Array(FRAME);
      for (let i = 200; i < 400; i++) f[i] = 0.45;
      return f; // rms ≈ 0.17, peak 0.45
    };
    const tail = () => new Float32Array(FRAME).fill(0.03);
    const fired: boolean[] = [];
    for (const f of [spike(), tail(), spike(), tail(), spike(), tail()]) fired.push(d.feed(f));
    expect(fired).toEqual([false, false, false, false, false, true]);
  });

  it('a long sustained spike (a shout, music) is discarded, not counted', () => {
    const d = make();
    // Impulse-like first frame, then energy STAYS up for many frames → speech-ish.
    d.feed(clap());
    for (let i = 0; i < 6; i++) d.feed(clap());
    expect(d.feed(silence())).toBe(false); // fall-back after a long spike ≠ clap
  });

  it('re-arms after firing', () => {
    const d = make();
    const round = () => {
      const fired: boolean[] = [];
      for (const f of [clap(), silence(), clap(), silence(), clap(), silence()]) fired.push(d.feed(f));
      return fired[fired.length - 1];
    };
    expect(round()).toBe(true);
    expect(round()).toBe(true);
  });

  it('uses the raw pre-downsample peak when provided (smeared frames still count)', () => {
    const d = make();
    // Downsampling smeared the frame: RMS 0.03, in-frame peak only 0.04 (crest too low)…
    const smeared = new Float32Array(FRAME).fill(0.03);
    // …but the RAW 48 kHz peak was 0.4 — clearly an impulse.
    expect(d.feed(smeared, 0.4)).toBe(false); // spike opens
    expect(d.feed(silence(), 0)).toBe(false); // clap 1 confirmed
    d.feed(smeared, 0.4);
    d.feed(silence(), 0);
    d.feed(smeared, 0.4);
    expect(d.feed(silence(), 0)).toBe(true); // clap 3 → wake
  });
});
