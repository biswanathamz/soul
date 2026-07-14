/**
 * UtteranceDetector — pure VAD-ish state machine (docs/voice-and-face.md §4.3).
 *
 * Fed fixed-rate PCM frames; waits for speech (RMS gate), records with a short
 * pre-roll, and finishes after trailing silence or a hard cap. Used both for
 * question capture and for local wake-word spotting (each utterance is
 * transcribed and matched against "hey soul").
 */

import { rmsOf } from './pcm';

export interface UtteranceConfig {
  sampleRate: number;
  /**
   * Minimum speech gate. The actual gate is ADAPTIVE — it tracks the ambient
   * noise floor and sits at ~3.5× above it, clamped to [startRms, 0.05] — so
   * quiet laptop mics and noisy rooms both work without hand-tuning.
   */
  startRms?: number;
  /** Trailing silence that ends the utterance. */
  endSilenceMs?: number;
  /** Hard cap on utterance length. */
  maxMs?: number;
  /** Audio kept from just before speech started (word onsets). */
  preRollMs?: number;
}

export type UtteranceStatus =
  | { state: 'waiting' }
  | { state: 'recording' }
  | { state: 'done'; audio: Float32Array };

export class UtteranceDetector {
  private readonly minGate: number;
  private readonly endSilenceSamples: number;
  private readonly maxSamples: number;
  private readonly preRollSamples: number;

  private preRoll: Float32Array[] = [];
  private preRollLen = 0;
  private chunks: Float32Array[] = [];
  private recordedLen = 0;
  private silentRun = 0;
  private recording = false;
  /** Ambient noise floor: drops instantly to quieter input, drifts up slowly. */
  private floor = Number.POSITIVE_INFINITY;

  constructor(cfg: UtteranceConfig) {
    const { sampleRate } = cfg;
    this.minGate = cfg.startRms ?? 0.006;
    this.endSilenceSamples = Math.round(((cfg.endSilenceMs ?? 1200) / 1000) * sampleRate);
    this.maxSamples = Math.round(((cfg.maxMs ?? 12000) / 1000) * sampleRate);
    this.preRollSamples = Math.round(((cfg.preRollMs ?? 300) / 1000) * sampleRate);
  }

  /** The current adaptive speech gate (exposed for diagnostics). */
  gate(): number {
    const floor = Number.isFinite(this.floor) ? this.floor : 0;
    return Math.min(Math.max(floor * 3.5, this.minGate), 0.05);
  }

  feed(frame: Float32Array): UtteranceStatus {
    const rms = rmsOf(frame);

    if (!this.recording) {
      // Track the noise floor only while waiting — speech shouldn't raise it.
      this.floor = Number.isFinite(this.floor)
        ? Math.min(rms, this.floor * 1.05 + 1e-6)
        : rms;
      // Keep a rolling pre-roll so the first syllable isn't clipped.
      this.preRoll.push(frame);
      this.preRollLen += frame.length;
      while (this.preRollLen - (this.preRoll[0]?.length ?? 0) >= this.preRollSamples) {
        this.preRollLen -= this.preRoll.shift()!.length;
      }
      if (rms >= this.gate()) {
        this.recording = true;
        this.chunks = [...this.preRoll];
        this.recordedLen = this.preRollLen;
        this.silentRun = 0;
      }
      return { state: this.recording ? 'recording' : 'waiting' };
    }

    this.chunks.push(frame);
    this.recordedLen += frame.length;
    // Hysteresis: easier to stay in speech (half the entry gate) than to enter.
    this.silentRun = rms < this.gate() * 0.5 ? this.silentRun + frame.length : 0;

    if (this.silentRun >= this.endSilenceSamples || this.recordedLen >= this.maxSamples) {
      return { state: 'done', audio: this.concat() };
    }
    return { state: 'recording' };
  }

  private concat(): Float32Array {
    const out = new Float32Array(this.recordedLen);
    let off = 0;
    for (const c of this.chunks) {
      out.set(c, off);
      off += c.length;
    }
    return out;
  }
}
