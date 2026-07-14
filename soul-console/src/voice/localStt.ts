/**
 * Local speech recognition (docs/voice-and-face.md §4.3, phase 4).
 *
 * Mic PCM → UtteranceDetector (VAD) → WAV → POST /voice/api/v1/stt
 * (faster-whisper on soul-voice). Nothing leaves the machine.
 *
 * Both entry points return the same `SttHandle` shape as the browser engine
 * (voice/stt.ts), so callers just pick an engine function.
 */

import { isMicSupported, startPcmCapture, STT_SAMPLE_RATE, type PcmCapture } from './audioCapture';
import { encodeWav } from './pcm';
import type { SttHandle, SttOptions } from './stt';
import { UtteranceDetector } from './utterance';

export const isLocalSttSupported = isMicSupported;

export async function transcribe(audio: Float32Array): Promise<string> {
  const res = await fetch('/voice/api/v1/stt', {
    method: 'POST',
    headers: { 'Content-Type': 'audio/wav' },
    body: encodeWav(audio, STT_SAMPLE_RATE),
  });
  if (!res.ok) throw new Error(`stt ${res.status}`);
  return ((await res.json()) as { text: string }).text.trim();
}

/** No speech within this window → give up the one-shot capture. */
const START_TIMEOUT_MS = 8000;

/** One-shot question capture — local counterpart of startRecognition(). */
export function startLocalRecognition(opts: SttOptions): SttHandle | null {
  if (!isLocalSttSupported()) return null;
  let capture: PcmCapture | null = null;
  let stopped = false;
  const detector = new UtteranceDetector({ sampleRate: STT_SAMPLE_RATE });
  let sawSpeech = false;

  const finish = () => {
    if (stopped) return;
    stopped = true;
    capture?.stop();
    capture = null;
    opts.onEnd?.();
  };

  const startTimer = setTimeout(() => {
    if (!sawSpeech) finish();
  }, START_TIMEOUT_MS);

  void startPcmCapture((frame) => {
    if (stopped) return;
    const status = detector.feed(frame);
    if (status.state === 'recording' && !sawSpeech) {
      sawSpeech = true;
      opts.onInterim?.('…');
    }
    if (status.state === 'done') {
      clearTimeout(startTimer);
      const audio = status.audio;
      // Stop the mic before the (possibly slow) transcription round-trip.
      capture?.stop();
      capture = null;
      transcribe(audio)
        .then((text) => {
          if (!stopped && text) opts.onFinal?.(text);
        })
        .catch(() => opts.onError?.('stt-unreachable'))
        .finally(finish);
    }
  }).then(
    (c) => {
      if (stopped) c.stop();
      else capture = c;
    },
    () => {
      clearTimeout(startTimer);
      opts.onError?.('mic-denied');
      finish();
    },
  );

  return {
    stop() {
      clearTimeout(startTimer);
      finish();
    },
  };
}

/**
 * Continuous local wake spotting: transcribe each detected utterance and hand
 * it to onFinal (the wake loop runs matchWake over it). Short utterance caps
 * keep the whisper calls tiny; silence sends nothing at all (VAD gate).
 */
export function startLocalWakeRecognition(opts: SttOptions): SttHandle | null {
  if (!isLocalSttSupported()) return null;
  let capture: PcmCapture | null = null;
  let stopped = false;
  let detector = newDetector();
  let busy = false; // don't stack transcriptions if speech is continuous

  function newDetector() {
    return new UtteranceDetector({
      sampleRate: STT_SAMPLE_RATE,
      endSilenceMs: 500,
      maxMs: 4000,
      preRollMs: 300,
    });
  }

  void startPcmCapture((frame) => {
    if (stopped || busy) return;
    const status = detector.feed(frame);
    if (status.state === 'done') {
      detector = newDetector();
      busy = true;
      transcribe(status.audio)
        .then((text) => {
          if (!stopped && text) opts.onFinal?.(text);
        })
        .catch(() => {
          if (!stopped) {
            opts.onError?.('stt-unreachable');
            handle.stop(); // service down — end the loop; wakeword.ts backs off + retries
          }
        })
        .finally(() => {
          busy = false;
        });
    }
  }).then(
    (c) => {
      if (stopped) c.stop();
      else capture = c;
    },
    () => {
      opts.onError?.('mic-denied');
      handle.stop();
    },
  );

  const handle: SttHandle = {
    stop() {
      if (stopped) return;
      stopped = true;
      capture?.stop();
      capture = null;
      opts.onEnd?.();
    },
  };
  return handle;
}
