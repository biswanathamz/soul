/** Web Speech API — speechSynthesis wrapper (TDD §7.2). */

export const isTtsSupported = (): boolean =>
  typeof window !== 'undefined' && 'speechSynthesis' in window;

export function listVoices(): SpeechSynthesisVoice[] {
  return isTtsSupported() ? window.speechSynthesis.getVoices() : [];
}

export function speak(text: string, opts: { voiceURI?: string | null; onEnd?: () => void } = {}): void {
  if (!isTtsSupported() || !text.trim()) {
    opts.onEnd?.();
    return;
  }
  const utterance = new SpeechSynthesisUtterance(text);
  if (opts.voiceURI) {
    const voice = listVoices().find((v) => v.voiceURI === opts.voiceURI);
    if (voice) utterance.voice = voice;
  }
  utterance.onend = () => opts.onEnd?.();
  utterance.onerror = () => opts.onEnd?.();
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(utterance);
}

export function cancelSpeech(): void {
  if (isTtsSupported()) window.speechSynthesis.cancel();
}

/** Markdown → speakable plain text; code blocks are announced, not read. */
export function stripMarkdownForSpeech(md: string): string {
  return md
    .replace(/```[\s\S]*?```/g, ' Code block omitted. ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/(\*\*|__|~~)/g, '')
    .replace(/(^|\s)[*_](\S[^*_]*\S)[*_](?=\s|$|[.,!?])/g, '$1$2')
    .replace(/^\s*\|.*\|\s*$/gm, ' ')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/\s+/g, ' ')
    .trim();
}
