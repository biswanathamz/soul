import { create } from 'zustand';
import { isLocalSttSupported, startLocalRecognition } from '../voice/localStt';
import { noteSpokenSentence } from '../voice/selfEcho';
import { isSttSupported, startRecognition, type SttHandle, type SttOptions } from '../voice/stt';
import { cancelSpeech, isTtsSupported, speak as ttsSpeak } from '../voice/tts';
import { useChatStore } from './chatStore';
import { useFaceStore, type FaceEvent } from './faceStore';
import { useSettingsStore } from './settingsStore';

const face = (e: FaceEvent) => useFaceStore.getState().apply(e);

type MicState = 'idle' | 'listening' | 'error';

interface VoiceState {
  supported: { stt: boolean; tts: boolean };
  micState: MicState;
  interim: string;
  speaking: boolean;
  /** True while the wake-word loop keeps the mic open in the background (§4.3). */
  wakeListening: boolean;
  init: () => void;
  startListening: () => void;
  stopListening: () => void;
  speak: (text: string) => void;
  stopSpeaking: () => void;
  /** Neural pipeline lifecycle — pauses listening while SOUL talks. */
  beginSpeech: () => void;
  endSpeech: () => void;
}

let handle: SttHandle | null = null;

/** speaker.ts registers its canceller here (avoids an import cycle). */
let neuralCancel: (() => void) | null = null;
export function registerNeuralCancel(fn: () => void): void {
  neuralCancel = fn;
}

/** Engine picker (§4.3 phase 4): local whisper is the default private path. */
export function startSttEngine(opts: SttOptions): SttHandle | null {
  const preferLocal = useSettingsStore.getState().sttEngine === 'local';
  if (preferLocal && isLocalSttSupported()) return startLocalRecognition(opts);
  return startRecognition(opts);
}

export const useVoiceStore = create<VoiceState>((set, get) => ({
  supported: { stt: false, tts: false },
  micState: 'idle',
  interim: '',
  speaking: false,
  wakeListening: false,

  init() {
    set({ supported: { stt: isSttSupported() || isLocalSttSupported(), tts: isTtsSupported() } });
  },

  startListening() {
    const { supported, micState } = get();
    if (!supported.stt || micState === 'listening') return;
    get().stopSpeaking();
    // One-shot utterance capture in every mode — silence ends it. Continuous
    // background listening belongs to the wake-word loop (voice/wakeword.ts).
    const started = startSttEngine({
      continuous: false,
      onInterim: (text) => set({ interim: text }),
      onFinal: (text) => {
        set({ interim: '' });
        if (text) {
          get().stopSpeaking();
          void useChatStore.getState().send(text);
        }
      },
      onError: () => {
        set({ micState: 'error', interim: '' });
        face({ type: 'stt.stop' });
      },
      onEnd: () => {
        handle = null;
        set((s) => (s.micState === 'listening' ? { micState: 'idle', interim: '' } : s));
        face({ type: 'stt.stop' });
      },
    });
    if (started) {
      handle = started;
      set({ micState: 'listening', interim: '' });
      face({ type: 'stt.start' });
    } else {
      set({ micState: 'error' });
    }
  },

  stopListening() {
    handle?.stop();
    handle = null;
    set({ micState: 'idle' });
    face({ type: 'stt.stop' });
  },

  speak(text) {
    const mode = useSettingsStore.getState().voiceMode;
    if (!get().supported.tts || mode === 'off' || !text) return;
    // Pause any capture so SOUL doesn't hear itself (TDD §7.1); the wake-word
    // loop resumes on its own once speaking ends.
    if (get().micState === 'listening') get().stopListening();
    set({ speaking: true });
    face({ type: 'speech.start' });
    face({ type: 'speech.sentence', text });
    noteSpokenSentence(text); // barge-in: the wake loop must not mistake this for the user
    ttsSpeak(text, {
      voiceURI: useSettingsStore.getState().ttsVoiceURI,
      onEnd: () => {
        set({ speaking: false });
        face({ type: 'speech.end' });
      },
    });
  },

  stopSpeaking() {
    cancelSpeech();
    neuralCancel?.();
    if (get().speaking) set({ speaking: false });
  },

  beginSpeech() {
    // Pause any capture so SOUL doesn't hear itself; the wake-word loop
    // (voice/wakeword.ts) resumes background listening after speech ends.
    if (get().micState === 'listening') get().stopListening();
    set({ speaking: true });
    face({ type: 'speech.start' });
  },

  endSpeech() {
    set({ speaking: false });
    face({ type: 'speech.end' });
  },
}));
