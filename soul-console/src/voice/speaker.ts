/**
 * Neural TTS playback (docs/voice-and-face.md §4.2).
 *
 * `createSpeaker` is the pure, testable queue engine: sentences are synthesized in
 * parallel but always PLAYED in order. `voiceReply` is the dispatcher-facing
 * pipeline — it feeds streamed tokens through the SentenceStream, speaks via
 * soul-voice, and falls back to browser speechSynthesis if the service is down.
 */

import { useFaceStore } from '../state/faceStore';
import { useSettingsStore } from '../state/settingsStore';
import { registerNeuralCancel, useVoiceStore } from '../state/voiceStore';
import { CODE_NOTICE, SentenceStream } from './sentences';
import { stripMarkdownForSpeech } from './tts';

export interface VoiceInfo {
  id: string;
  lang: string;
  gender: string | null;
  default: boolean;
}

export async function listSoulVoices(): Promise<VoiceInfo[]> {
  const res = await fetch('/voice/api/v1/voices');
  if (!res.ok) throw new Error(`voices ${res.status}`);
  return (await res.json()) as VoiceInfo[];
}

export async function fetchTtsBlob(text: string): Promise<Blob> {
  const res = await fetch('/voice/api/v1/tts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      text,
      voice: useSettingsStore.getState().soulVoiceId ?? undefined,
    }),
  });
  if (!res.ok) throw new Error(`tts ${res.status}`);
  return res.blob();
}

// ---------------------------------------------------------------------------
// Queue engine
// ---------------------------------------------------------------------------

export interface SpeakerDeps {
  synth: (text: string) => Promise<Blob>;
  /** Resolves when playback of this blob finishes (or is stopped). */
  play: (blob: Blob) => Promise<void>;
  stop?: () => void;
  onStart?: () => void;
  /** Fires as each sentence begins playing — drives the face caption. */
  onSentence?: (text: string) => void;
  /** Fires exactly once, after finish() and the queue draining. */
  onEnd?: (spokeAny: boolean) => void;
  /** First synthesis failed before anything played — the service looks down. */
  onNeuralDown?: () => void;
}

export interface Speaker {
  enqueue(text: string): void;
  finish(): void;
  cancel(): void;
}

export function createSpeaker(deps: SpeakerDeps): Speaker {
  const items: { p: Promise<Blob | null>; text: string }[] = [];
  let pumping = false;
  let spoke = 0;
  let started = false;
  let finished = false;
  let cancelled = false;
  let ended = false;

  const maybeEnd = () => {
    if (finished && !pumping && items.length === 0 && !ended) {
      ended = true;
      deps.onEnd?.(spoke > 0);
    }
  };

  const pump = async () => {
    if (pumping) return;
    pumping = true;
    while (items.length > 0 && !cancelled) {
      const item = items.shift()!;
      const blob = await item.p;
      if (cancelled) break;
      if (blob === null) {
        if (spoke === 0) {
          // Nothing has played and the very first request failed — bail out so the
          // pipeline can fall back to browser TTS with the full text.
          cancelled = true;
          items.length = 0;
          deps.onNeuralDown?.();
          break;
        }
        continue; // transient mid-stream failure — skip this sentence
      }
      if (!started) {
        started = true;
        deps.onStart?.();
      }
      spoke += 1;
      deps.onSentence?.(item.text);
      await deps.play(blob);
    }
    pumping = false;
    maybeEnd();
  };

  return {
    enqueue(text: string) {
      if (cancelled || finished) return;
      items.push({ p: deps.synth(text).catch(() => null), text });
      void pump();
    },
    finish() {
      finished = true;
      maybeEnd();
    },
    cancel() {
      cancelled = true;
      finished = true;
      items.length = 0;
      deps.stop?.();
      maybeEnd();
    },
  };
}

// ---------------------------------------------------------------------------
// Default audio playback (browser)
// ---------------------------------------------------------------------------

let livePlayback: { audio: HTMLAudioElement; done: () => void } | null = null;

function playBlob(blob: Blob): Promise<void> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    const done = () => {
      URL.revokeObjectURL(url);
      if (livePlayback?.audio === audio) livePlayback = null;
      resolve();
    };
    livePlayback = { audio, done };
    audio.onended = done;
    audio.onerror = done;
    void audio.play().catch(done);
  });
}

function stopPlayback(): void {
  if (livePlayback) {
    livePlayback.audio.pause();
    livePlayback.done();
  }
}

// ---------------------------------------------------------------------------
// The reply pipeline the dispatcher talks to
// ---------------------------------------------------------------------------

interface ReplyCtx {
  messageId: string;
  splitter: SentenceStream;
  speaker: Speaker;
  dead: boolean; // soul-voice unreachable for this turn
  doneText: string | null;
}

let current: ReplyCtx | null = null;

const voiceEnabled = () => useSettingsStore.getState().voiceMode !== 'off';

function speakable(sentence: string): string {
  return sentence === CODE_NOTICE ? sentence : stripMarkdownForSpeech(sentence);
}

function startCtx(messageId: string): ReplyCtx {
  const ctx: ReplyCtx = {
    messageId,
    splitter: new SentenceStream(),
    speaker: null as unknown as Speaker,
    dead: false,
    doneText: null,
  };
  ctx.speaker = createSpeaker({
    synth: fetchTtsBlob,
    play: playBlob,
    stop: stopPlayback,
    onStart: () => useVoiceStore.getState().beginSpeech(),
    onSentence: (text) => useFaceStore.getState().apply({ type: 'speech.sentence', text }),
    onEnd: (spokeAny) => {
      if (spokeAny) useVoiceStore.getState().endSpeech();
    },
    onNeuralDown: () => {
      ctx.dead = true;
      if (ctx.doneText !== null) {
        // task.done already arrived — speak the whole reply the old way, now.
        useVoiceStore.getState().speak(ctx.doneText);
      }
    },
  });
  return ctx;
}

function feed(ctx: ReplyCtx, sentences: string[]): void {
  if (ctx.dead) return;
  for (const s of sentences) {
    const text = speakable(s);
    if (/[a-z0-9]/i.test(text)) ctx.speaker.enqueue(text);
  }
}

export const voiceReply = {
  onToken(messageId: string, token: string): void {
    if (!voiceEnabled()) return;
    if (current && current.messageId !== messageId) this.cancel();
    if (!current) current = startCtx(messageId);
    feed(current, current.splitter.push(token));
  },

  onDone(text: string): void {
    if (!voiceEnabled()) {
      current = null;
      return;
    }
    const full = stripMarkdownForSpeech(text);
    if (!current) {
      // Nothing streamed (e.g. instant/stub reply) — speak it as one utterance.
      current = startCtx('(done)');
      current.doneText = full;
      feed(current, [full]);
    } else {
      current.doneText = full;
      feed(current, current.splitter.flush());
    }
    if (current.dead) {
      useVoiceStore.getState().speak(full);
      current = null;
      return;
    }
    current.speaker.finish();
    // Keep ctx until the next turn replaces it — audio may still be draining.
  },

  cancel(): void {
    if (current) {
      current.speaker.cancel();
      current = null;
      useVoiceStore.getState().endSpeech();
    }
  },
};

// Let voiceStore.stopSpeaking() halt neural audio without an import cycle.
registerNeuralCancel(() => voiceReply.cancel());
