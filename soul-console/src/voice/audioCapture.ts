/** Raw mic PCM capture — feeds the local STT path with 16 kHz mono frames. */

import { downsample, peakOf } from './pcm';

export const STT_SAMPLE_RATE = 16000;

export interface PcmCapture {
  stop(): void;
}

export const isMicSupported = (): boolean =>
  typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;

/**
 * Opens the microphone and streams downsampled PCM frames to `onFrame`.
 * ScriptProcessorNode is deprecated but universally supported and plenty for
 * 16 kHz capture; revisit with AudioWorklet if it ever bites.
 */
/**
 * `onFrame` gets the 16 kHz frame plus the RAW 48 kHz peak of the same window —
 * the averaging downsampler low-passes sharp transients, so clap detection
 * must read the peak before it's smeared.
 */
export async function startPcmCapture(
  onFrame: (pcm: Float32Array, rawPeak: number) => void,
): Promise<PcmCapture> {
  const stream = await navigator.mediaDevices.getUserMedia({
    // noiseSuppression OFF: Chrome's suppressor treats impulsive sounds (claps!)
    // as noise and erases them; whisper copes fine with unsuppressed audio.
    // echoCancellation ON, but do not rely on it for SOUL's own voice: browser
    // AEC only reliably cancels WebRTC remote streams, and our TTS plays via a
    // plain <audio> element — to the canceller that's room noise. Barge-in's
    // real protection is the text-level filter in selfEcho.ts.
    audio: { echoCancellation: true, noiseSuppression: false, autoGainControl: true },
  });
  const ctx = new AudioContext();

  // Autoplay policy: an AudioContext created without a user gesture starts
  // 'suspended' and delivers ZERO frames — which silently kills wake-word
  // listening started at page load. Resume now if allowed; otherwise resume
  // on the next gesture (click/keypress).
  const resume = () => {
    if (ctx.state === 'suspended') {
      void ctx.resume().catch(() => {
        /* not allowed yet — the next gesture will retry */
      });
    }
  };
  resume();
  if (ctx.state === 'suspended') {
    console.debug('[voice] mic audio context suspended — will resume on first click/keypress');
  }
  window.addEventListener('pointerdown', resume);
  window.addEventListener('keydown', resume);

  const source = ctx.createMediaStreamSource(stream);
  const processor = ctx.createScriptProcessor(4096, 1, 1);
  processor.onaudioprocess = (e) => {
    const raw = e.inputBuffer.getChannelData(0);
    onFrame(downsample(raw, ctx.sampleRate, STT_SAMPLE_RATE), peakOf(raw));
  };
  source.connect(processor);
  processor.connect(ctx.destination); // required in some browsers for the node to run
  return {
    stop() {
      window.removeEventListener('pointerdown', resume);
      window.removeEventListener('keydown', resume);
      processor.disconnect();
      source.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      void ctx.close();
    },
  };
}
