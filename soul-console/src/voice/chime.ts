/** Two-tone wake chime — WebAudio, no asset file needed. */

let ctx: AudioContext | null = null;

export function chime(): void {
  try {
    const w = window as unknown as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext };
    const Ctor = w.AudioContext ?? w.webkitAudioContext;
    if (!Ctor) return;
    ctx ??= new Ctor();
    const t = ctx.currentTime;
    for (const [freq, start] of [
      [880, 0],
      [1318.5, 0.09],
    ] as const) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, t + start);
      gain.gain.exponentialRampToValueAtTime(0.12, t + start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + start + 0.25);
      osc.connect(gain).connect(ctx.destination);
      osc.start(t + start);
      osc.stop(t + start + 0.3);
    }
  } catch {
    /* no audio — the face going to "listening" still signals the wake */
  }
}
