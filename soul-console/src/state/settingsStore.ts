import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type VoiceMode = 'off' | 'ptt' | 'handsfree';
export type SttEngine = 'local' | 'browser';

interface SettingsState {
  voiceMode: VoiceMode;
  /** local = whisper on soul-voice (private); browser = Web Speech (cloud-backed in Chrome). */
  sttEngine: SttEngine;
  /**
   * Keep the mic open while SOUL speaks so "Hey SOUL" can interrupt her (local engine).
   * Off by default: with the mic live during playback her own neural-TTS voice can trip
   * the wake word and cut her off (docs/bug/latency-and-tts-interruption.md). Opt in on
   * headphones, where there's no speaker→mic echo path.
   */
  bargeIn: boolean;
  /** Wake SOUL with three claps 👏👏👏 (local engine only). */
  clapWake: boolean;
  /** Neural voice id on soul-voice (null = service default). */
  soulVoiceId: string | null;
  /** Browser speechSynthesis voice — fallback when soul-voice is down. */
  ttsVoiceURI: string | null;
  reducedMotion: boolean;
  setVoiceMode: (mode: VoiceMode) => void;
  setSttEngine: (engine: SttEngine) => void;
  setBargeIn: (on: boolean) => void;
  setClapWake: (on: boolean) => void;
  setSoulVoice: (id: string | null) => void;
  setTtsVoice: (uri: string | null) => void;
  setReducedMotion: (reduced: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      voiceMode: 'ptt',
      sttEngine: 'local',
      bargeIn: false,
      clapWake: true,
      soulVoiceId: null,
      ttsVoiceURI: null,
      reducedMotion: false,
      setVoiceMode: (voiceMode) => set({ voiceMode }),
      setSttEngine: (sttEngine) => set({ sttEngine }),
      setBargeIn: (bargeIn) => set({ bargeIn }),
      setClapWake: (clapWake) => set({ clapWake }),
      setSoulVoice: (soulVoiceId) => set({ soulVoiceId }),
      setTtsVoice: (ttsVoiceURI) => set({ ttsVoiceURI }),
      setReducedMotion: (reducedMotion) => set({ reducedMotion }),
    }),
    {
      name: 'soul.settings.v1',
      // Bump when a persisted default must be corrected for existing testers. v1 turns
      // barge-in off once (it shipped on by default and cut SOUL off mid-sentence); the
      // user's other saved settings are preserved, and they can re-enable it in Settings.
      version: 1,
      migrate: (persisted, from) => {
        const state = persisted as Partial<SettingsState>;
        if (from < 1) state.bargeIn = false;
        return state as SettingsState;
      },
    },
  ),
);
