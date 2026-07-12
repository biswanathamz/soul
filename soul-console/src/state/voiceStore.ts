import { create } from 'zustand';
import { isSttSupported, startRecognition, type SttHandle } from '../voice/stt';
import { cancelSpeech, isTtsSupported, speak as ttsSpeak } from '../voice/tts';
import { useChatStore } from './chatStore';
import { useSettingsStore } from './settingsStore';

type MicState = 'idle' | 'listening' | 'error';

interface VoiceState {
  supported: { stt: boolean; tts: boolean };
  micState: MicState;
  interim: string;
  speaking: boolean;
  init: () => void;
  startListening: () => void;
  stopListening: () => void;
  speak: (text: string) => void;
  stopSpeaking: () => void;
}

let handle: SttHandle | null = null;

export const useVoiceStore = create<VoiceState>((set, get) => ({
  supported: { stt: false, tts: false },
  micState: 'idle',
  interim: '',
  speaking: false,

  init() {
    set({ supported: { stt: isSttSupported(), tts: isTtsSupported() } });
  },

  startListening() {
    const { supported, micState } = get();
    if (!supported.stt || micState === 'listening') return;
    const handsfree = useSettingsStore.getState().voiceMode === 'handsfree';
    get().stopSpeaking();
    const started = startRecognition({
      continuous: handsfree,
      onInterim: (text) => set({ interim: text }),
      onFinal: (text) => {
        set({ interim: '' });
        if (text) {
          get().stopSpeaking();
          void useChatStore.getState().send(text);
        }
      },
      onError: () => set({ micState: 'error', interim: '' }),
      onEnd: () => {
        handle = null;
        set((s) => (s.micState === 'listening' ? { micState: 'idle', interim: '' } : s));
      },
    });
    if (started) {
      handle = started;
      set({ micState: 'listening', interim: '' });
    } else {
      set({ micState: 'error' });
    }
  },

  stopListening() {
    handle?.stop();
    handle = null;
    set({ micState: 'idle' });
  },

  speak(text) {
    const mode = useSettingsStore.getState().voiceMode;
    if (!get().supported.tts || mode === 'off' || !text) return;
    // Pause hands-free recognition so SOUL doesn't hear itself (TDD §7.1).
    const wasHandsfreeListening = mode === 'handsfree' && get().micState === 'listening';
    if (wasHandsfreeListening) get().stopListening();
    set({ speaking: true });
    ttsSpeak(text, {
      voiceURI: useSettingsStore.getState().ttsVoiceURI,
      onEnd: () => {
        set({ speaking: false });
        if (wasHandsfreeListening && useSettingsStore.getState().voiceMode === 'handsfree') {
          get().startListening();
        }
      },
    });
  },

  stopSpeaking() {
    cancelSpeech();
    if (get().speaking) set({ speaking: false });
  },
}));
