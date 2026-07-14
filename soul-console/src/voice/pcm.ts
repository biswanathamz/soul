/** PCM helpers for the local STT path (docs/voice-and-face.md §4.3, phase 4). */

/** Average-bucket downsample (e.g. 48 kHz mic → 16 kHz whisper input). */
export function downsample(input: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (fromRate === toRate) return input;
  const ratio = fromRate / toRate;
  const out = new Float32Array(Math.floor(input.length / ratio));
  for (let o = 0; o < out.length; o++) {
    const start = Math.floor(o * ratio);
    const end = Math.min(Math.floor((o + 1) * ratio), input.length);
    let sum = 0;
    for (let i = start; i < end; i++) sum += input[i];
    out[o] = end > start ? sum / (end - start) : 0;
  }
  return out;
}

export function rmsOf(frame: Float32Array): number {
  let sum = 0;
  for (let i = 0; i < frame.length; i++) sum += frame[i] * frame[i];
  return Math.sqrt(sum / (frame.length || 1));
}

/** 16-bit mono PCM WAV bytes — what /voice/api/v1/stt expects. */
export function pcmToWavBytes(pcm: Float32Array, sampleRate: number): ArrayBuffer {
  const buf = new ArrayBuffer(44 + pcm.length * 2);
  const v = new DataView(buf);
  const str = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i));
  };
  str(0, 'RIFF');
  v.setUint32(4, 36 + pcm.length * 2, true);
  str(8, 'WAVE');
  str(12, 'fmt ');
  v.setUint32(16, 16, true); // fmt chunk size
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true); // byte rate
  v.setUint16(32, 2, true); // block align
  v.setUint16(34, 16, true); // bits per sample
  str(36, 'data');
  v.setUint32(40, pcm.length * 2, true);
  for (let i = 0; i < pcm.length; i++) {
    const s = Math.max(-1, Math.min(1, pcm[i]));
    v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return buf;
}

export function encodeWav(pcm: Float32Array, sampleRate: number): Blob {
  return new Blob([pcmToWavBytes(pcm, sampleRate)], { type: 'audio/wav' });
}
