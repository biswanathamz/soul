import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type VoiceMode = 'off' | 'ptt' | 'handsfree';

interface SettingsState {
  voiceMode: VoiceMode;
  /** Neural voice id on soul-voice (null = service default). */
  soulVoiceId: string | null;
  /** Browser speechSynthesis voice — fallback when soul-voice is down. */
  ttsVoiceURI: string | null;
  reducedMotion: boolean;
  setVoiceMode: (mode: VoiceMode) => void;
  setSoulVoice: (id: string | null) => void;
  setTtsVoice: (uri: string | null) => void;
  setReducedMotion: (reduced: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      voiceMode: 'ptt',
      soulVoiceId: null,
      ttsVoiceURI: null,
      reducedMotion: false,
      setVoiceMode: (voiceMode) => set({ voiceMode }),
      setSoulVoice: (soulVoiceId) => set({ soulVoiceId }),
      setTtsVoice: (ttsVoiceURI) => set({ ttsVoiceURI }),
      setReducedMotion: (reducedMotion) => set({ reducedMotion }),
    }),
    { name: 'soul.settings.v1' },
  ),
);
