import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type VoiceMode = 'off' | 'ptt' | 'handsfree';

interface SettingsState {
  voiceMode: VoiceMode;
  ttsVoiceURI: string | null;
  reducedMotion: boolean;
  setVoiceMode: (mode: VoiceMode) => void;
  setTtsVoice: (uri: string | null) => void;
  setReducedMotion: (reduced: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      voiceMode: 'ptt',
      ttsVoiceURI: null,
      reducedMotion: false,
      setVoiceMode: (voiceMode) => set({ voiceMode }),
      setTtsVoice: (ttsVoiceURI) => set({ ttsVoiceURI }),
      setReducedMotion: (reducedMotion) => set({ reducedMotion }),
    }),
    { name: 'soul.settings.v1' },
  ),
);
