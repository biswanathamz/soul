/**
 * Wake word — "Hey SOUL" / "Hi SOUL" (docs/voice-and-face.md §4.3).
 *
 * Two engines, chosen in Settings:
 * - local (default): VAD-gated utterance spotting via whisper on soul-voice —
 *   fully private, and supports barge-in (interrupt SOUL while she speaks).
 * - browser: continuous Web Speech session (cloud-backed in Chrome). Chrome
 *   kills such sessions after ~60 s of silence, so the loop auto-restarts.
 * Wake mode stays off by default either way.
 */

import { useChatStore } from '../state/chatStore';
import { useSettingsStore } from '../state/settingsStore';
import { useVoiceStore } from '../state/voiceStore';
import { chime } from './chime';
import { isLocalSttSupported, startLocalWakeRecognition } from './localStt';
import { isSttSupported, startRecognition, type SttHandle } from './stt';

// 'seoul' / 'sole' / 'saul' / 'sol' are common recognizer mishears of "soul".
const WAKE_RE = /\b(?:hey|hi)[,!.]?\s+(?:soul|seoul|sole|saul|sol)\b[,!.:;]?\s*/i;

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

/** Local whisper spotting when available (private, phase 4); browser Web Speech otherwise. */
function localEngineActive(): boolean {
  return useSettingsStore.getState().sttEngine === 'local' && isLocalSttSupported();
}

/**
 * The loop runs only in wake-word mode and never during active capture.
 * While SOUL speaks it normally pauses — except local engine + barge-in,
 * where the ear stays open so "Hey SOUL" can interrupt her (§4.3).
 */
function shouldListen(): boolean {
  const v = useVoiceStore.getState();
  const s = useSettingsStore.getState();
  if (s.voiceMode !== 'handsfree' || v.micState === 'listening') return false;
  const local = localEngineActive();
  if (!local && !isSttSupported()) return false;
  if (v.speaking && !(local && s.bargeIn)) return false;
  return true;
}

function onWake(remainder: string): void {
  stopLoop();
  useVoiceStore.getState().stopSpeaking(); // barge-in: silence SOUL the moment she's named
  chime();
  if (remainder) {
    // "Hey SOUL, what time is it?" — the question came in the same breath.
    void useChatStore.getState().send(remainder);
  } else {
    // "Hey SOUL" alone — open the mic for the question.
    useVoiceStore.getState().startListening();
  }
}

function startLoop(): void {
  if (bg || !shouldListen()) return;
  const common = {
    continuous: true,
    onFinal: (text: string) => {
      const { matched, remainder } = matchWake(text);
      if (matched) onWake(remainder);
    },
    onError: (err: string) => {
      // Visible at default console level — mic-denied would otherwise fail silently.
      console.warn('[voice] wake listening error:', err);
    },
    onEnd: () => {
      bg = null;
      setWakeListening(false);
      scheduleRestart(); // sessions end (Chrome timeout / service blip) — keep the ear open
    },
  };
  bg = localEngineActive()
    ? startLocalWakeRecognition({
        ...common,
        // Triple clap 👏👏👏 wakes SOUL too — same flow as saying her name.
        onClaps: useSettingsStore.getState().clapWake ? () => onWake('') : undefined,
      })
    : startRecognition(common);
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
    if (
      s.voiceMode !== prev.voiceMode ||
      s.sttEngine !== prev.sttEngine ||
      s.bargeIn !== prev.bargeIn ||
      s.clapWake !== prev.clapWake
    ) {
      // Engine/mode/trigger changed — tear down so the next start picks it up.
      stopLoop();
      sync();
    }
  });
  useVoiceStore.subscribe((s, prev) => {
    if (s.speaking !== prev.speaking || s.micState !== prev.micState) sync();
  });
  sync();
}
