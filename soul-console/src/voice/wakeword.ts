/**
 * Wake word — "Hey SOUL" / "Hi SOUL" (docs/voice-and-face.md §4.3).
 *
 * v1 approach: a continuous Web Speech recognition session runs in the background
 * and transcripts are matched against the wake phrase. Chrome kills such sessions
 * after ~60 s of silence, so the loop auto-restarts. Off by default (browser STT
 * is cloud-backed in Chrome — see the doc's privacy note); Phase 4 replaces this
 * with a local WASM engine.
 */

import { useChatStore } from '../state/chatStore';
import { useSettingsStore } from '../state/settingsStore';
import { useVoiceStore } from '../state/voiceStore';
import { chime } from './chime';
import { isSttSupported, startRecognition, type SttHandle } from './stt';

// 'seoul' / 'sole' are common recognizer mishears of "soul".
const WAKE_RE = /\b(?:hey|hi)[,!.]?\s+(?:soul|seoul|sole)\b[,!.:;]?\s*/i;

/** Pure matcher: did the transcript wake SOUL, and what was said after the name? */
export function matchWake(transcript: string): { matched: boolean; remainder: string } {
  const m = WAKE_RE.exec(transcript);
  if (!m) return { matched: false, remainder: '' };
  return { matched: true, remainder: transcript.slice(m.index + m[0].length).trim() };
}

// ---------------------------------------------------------------------------
// Background listening loop
// ---------------------------------------------------------------------------

let bg: SttHandle | null = null;
let restartTimer: ReturnType<typeof setTimeout> | null = null;

const setWakeListening = (wakeListening: boolean) => {
  if (useVoiceStore.getState().wakeListening !== wakeListening) {
    useVoiceStore.setState({ wakeListening });
  }
};

/** The loop should run only in wake-word mode, and never while SOUL speaks or captures. */
function shouldListen(): boolean {
  const v = useVoiceStore.getState();
  return (
    useSettingsStore.getState().voiceMode === 'handsfree' &&
    !v.speaking &&
    v.micState !== 'listening' &&
    isSttSupported()
  );
}

function onWake(remainder: string): void {
  stopLoop();
  chime();
  if (remainder) {
    // "Hey SOUL, what time is it?" — the question came in the same breath.
    useVoiceStore.getState().stopSpeaking();
    void useChatStore.getState().send(remainder);
  } else {
    // "Hey SOUL" alone — open the mic for the question.
    useVoiceStore.getState().startListening();
  }
}

function startLoop(): void {
  if (bg || !shouldListen()) return;
  bg = startRecognition({
    continuous: true,
    onFinal: (text) => {
      const { matched, remainder } = matchWake(text);
      if (matched) onWake(remainder);
    },
    onError: () => {
      /* onEnd fires next and schedules the restart */
    },
    onEnd: () => {
      bg = null;
      setWakeListening(false);
      scheduleRestart(); // Chrome times sessions out — keep the ear open
    },
  });
  setWakeListening(bg !== null);
}

function stopLoop(): void {
  if (restartTimer) {
    clearTimeout(restartTimer);
    restartTimer = null;
  }
  if (bg) {
    const h = bg;
    bg = null; // clear first so onEnd doesn't schedule a restart loop
    h.stop();
  }
  setWakeListening(false);
}

function scheduleRestart(delayMs = 400): void {
  if (restartTimer) clearTimeout(restartTimer);
  restartTimer = setTimeout(() => {
    restartTimer = null;
    startLoop();
  }, delayMs);
}

function sync(): void {
  if (shouldListen()) {
    if (!bg) scheduleRestart(50);
  } else {
    stopLoop();
  }
}

/** Call once at boot — watches settings + voice state and keeps the loop in sync. */
export function initWakeWord(): void {
  useSettingsStore.subscribe((s, prev) => {
    if (s.voiceMode !== prev.voiceMode) sync();
  });
  useVoiceStore.subscribe((s, prev) => {
    if (s.speaking !== prev.speaking || s.micState !== prev.micState) sync();
  });
  sync();
}
