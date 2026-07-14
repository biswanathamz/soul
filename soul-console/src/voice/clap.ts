/**
 * Triple-clap wake trigger 👏👏👏 (docs/voice-and-face.md §4.3).
 *
 * Pure DSP on the same PCM frames the local wake loop already captures — no
 * whisper involved. A clap shows up as an energy SPIKE: a frame jumping well
 * above the ambient floor, impulse-like (raw peak ≫ frame RMS), that falls
 * back within a frame or two — sustained speech rises and STAYS up, so it
 * fails the fall-back test. Everything is relative to the tracked noise
 * floor, so mic volume and Chrome's processing don't need hand-tuning.
 */

import { peakOf, rmsOf } from './pcm';

export interface ClapConfig {
  sampleRate: number;
  /** Absolute floor for a spike — below this it's too quiet to be a clap. */
  minRms?: number;
  /** Max pause between claps before the count resets. */
  maxGapMs?: number;
  /** How many claps trigger the wake. */
  claps?: number;
  /** Diagnostics sink (e.g. console.debug). */
  debug?: (msg: string) => void;
}

export class ClapDetector {
  private readonly minRms: number;
  private readonly maxGapSamples: number;
  private readonly needed: number;
  private readonly debug?: (msg: string) => void;

  private count = 0;
  private sinceLast = 0;
  /** Ambient floor: drops fast, rises slowly; not updated during spikes. */
  private floor = Number.POSITIVE_INFINITY;
  private spikeFrames = 0; // 0 = quiet; >0 = inside a spike
  private spikeMax = 0; // loudest frame RMS within the current spike

  constructor(cfg: ClapConfig) {
    this.minRms = cfg.minRms ?? 0.015;
    this.maxGapSamples = Math.round(((cfg.maxGapMs ?? 1500) / 1000) * cfg.sampleRate);
    this.needed = cfg.claps ?? 3;
    this.debug = cfg.debug;
  }

  /**
   * Feed one PCM frame (plus, ideally, the raw pre-downsample peak — the
   * averaging downsampler smears transients). Returns true exactly when the
   * Nth clap is confirmed.
   */
  feed(frame: Float32Array, rawPeak?: number): boolean {
    const rms = rmsOf(frame);
    const peak = rawPeak ?? peakOf(frame);

    if (this.count > 0) {
      this.sinceLast += frame.length;
      if (this.sinceLast > this.maxGapSamples) {
        this.debug?.(`clap: too slow — count reset from ${this.count}`);
        this.count = 0;
      }
    }

    const floor = Number.isFinite(this.floor) ? this.floor : 0;
    const gate = Math.max(floor * 5, this.minRms);
    const release = Math.max(floor * 2.5, this.minRms * 0.5);

    if (this.spikeFrames === 0) {
      if (rms >= gate && peak >= rms * 1.8) {
        this.spikeFrames = 1; // impulse-like jump out of quiet — candidate clap
        this.spikeMax = rms;
      } else {
        // Track the floor only in quiet — spikes/speech must not raise it.
        this.floor = Number.isFinite(this.floor)
          ? Math.min(rms, this.floor * 1.05 + 1e-6)
          : rms;
      }
      return false;
    }

    // Inside a spike. A clap DECAYS — room reverb and AGC keep the tail well
    // above the noise floor, so "fell back" is judged against the spike's own
    // height (30% of its loudest frame), not against silence. Speech holds its
    // level instead of decaying and gets discarded.
    this.spikeMax = Math.max(this.spikeMax, rms);
    if (rms < Math.max(this.spikeMax * 0.3, release)) {
      const wasClap = this.spikeFrames <= 3;
      this.spikeFrames = 0;
      if (!wasClap) return false;
      this.count += 1;
      this.sinceLast = 0;
      this.debug?.(`clap ${this.count}/${this.needed}`);
      if (this.count >= this.needed) {
        this.count = 0;
        return true;
      }
    } else {
      this.spikeFrames += 1;
      if (this.spikeFrames > 4) {
        this.debug?.('clap: spike sustained — sounds like speech, discarding');
        this.spikeFrames = Number.MAX_SAFE_INTEGER; // stay discarded until quiet
      }
    }
    return false;
  }
}
