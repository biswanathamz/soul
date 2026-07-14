/** Raw mic PCM capture — feeds the local STT path with 16 kHz mono frames. */

import { downsample } from './pcm';

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
export async function startPcmCapture(onFrame: (pcm: Float32Array) => void): Promise<PcmCapture> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  });
  const ctx = new AudioContext();
  const source = ctx.createMediaStreamSource(stream);
  const processor = ctx.createScriptProcessor(4096, 1, 1);
  processor.onaudioprocess = (e) => {
    onFrame(downsample(e.inputBuffer.getChannelData(0), ctx.sampleRate, STT_SAMPLE_RATE));
  };
  source.connect(processor);
  processor.connect(ctx.destination); // required in some browsers for the node to run
  return {
    stop() {
      processor.disconnect();
      source.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      void ctx.close();
    },
  };
}
