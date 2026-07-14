import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type VoiceMode = 'off' | 'ptt' | 'handsfree';
export type SttEngine = 'local' | 'browser';

interface SettingsState {
  voiceMode: VoiceMode;
  /** local = whisper on soul-voice (private); browser = Web Speech (cloud-backed in Chrome). */
  sttEngine: SttEngine;
  /** Say "Hey SOUL" while she's talking to interrupt (local engine only). */
  bargeIn: boolean;
  /** Neural voice id on soul-voice (null = service default). */
  soulVoiceId: string | null;
  /** Browser speechSynthesis voice — fallback when soul-voice is down. */
  ttsVoiceURI: string | null;
  reducedMotion: boolean;
  setVoiceMode: (mode: VoiceMode) => void;
  setSttEngine: (engine: SttEngine) => void;
  setBargeIn: (on: boolean) => void;
  setSoulVoice: (id: string | null) => void;
  setTtsVoice: (uri: string | null) => void;
  setReducedMotion: (reduced: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      voiceMode: 'ptt',
      sttEngine: 'local',
      bargeIn: true,
      soulVoiceId: null,
      ttsVoiceURI: null,
      reducedMotion: false,
      setVoiceMode: (voiceMode) => set({ voiceMode }),
      setSttEngine: (sttEngine) => set({ sttEngine }),
      setBargeIn: (bargeIn) => set({ bargeIn }),
      setSoulVoice: (soulVoiceId) => set({ soulVoiceId }),
      setTtsVoice: (ttsVoiceURI) => set({ ttsVoiceURI }),
      setReducedMotion: (reducedMotion) => set({ reducedMotion }),
    }),
    { name: 'soul.settings.v1' },
  ),
);
