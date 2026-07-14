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
  /** RMS above this counts as speech. */
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
  private readonly startRms: number;
  private readonly endRms: number;
  private readonly endSilenceSamples: number;
  private readonly maxSamples: number;
  private readonly preRollSamples: number;

  private preRoll: Float32Array[] = [];
  private preRollLen = 0;
  private chunks: Float32Array[] = [];
  private recordedLen = 0;
  private silentRun = 0;
  private recording = false;

  constructor(cfg: UtteranceConfig) {
    const { sampleRate } = cfg;
    this.startRms = cfg.startRms ?? 0.015;
    this.endRms = this.startRms * 0.6; // hysteresis — easier to stay in speech than to enter
    this.endSilenceSamples = Math.round(((cfg.endSilenceMs ?? 1200) / 1000) * sampleRate);
    this.maxSamples = Math.round(((cfg.maxMs ?? 12000) / 1000) * sampleRate);
    this.preRollSamples = Math.round(((cfg.preRollMs ?? 300) / 1000) * sampleRate);
  }

  feed(frame: Float32Array): UtteranceStatus {
    const rms = rmsOf(frame);

    if (!this.recording) {
      // Keep a rolling pre-roll so the first syllable isn't clipped.
      this.preRoll.push(frame);
      this.preRollLen += frame.length;
      while (this.preRollLen - (this.preRoll[0]?.length ?? 0) >= this.preRollSamples) {
        this.preRollLen -= this.preRoll.shift()!.length;
      }
      if (rms >= this.startRms) {
        this.recording = true;
        this.chunks = [...this.preRoll];
        this.recordedLen = this.preRollLen;
        this.silentRun = 0;
      }
      return { state: this.recording ? 'recording' : 'waiting' };
    }

    this.chunks.push(frame);
    this.recordedLen += frame.length;
    this.silentRun = rms < this.endRms ? this.silentRun + frame.length : 0;

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
