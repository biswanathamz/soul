/**
 * SOUL's face — the two-layer state machine (docs/voice-and-face.md §3.1–3.2).
 *
 * `reduceFace` is a pure reducer over activity flags + mood + caption, driven by
 * events from the voice pipeline, the WS dispatcher, and the connection store.
 * Activity priority: listening > speaking > thinking > idle. Mood is a modifier
 * (`pleased` decays on a timer; `concerned` clears on the next activity).
 */

import { create } from 'zustand';

export type FaceActivity = 'idle' | 'listening' | 'thinking' | 'speaking';
export type FaceMood = 'neutral' | 'pleased' | 'concerned';

export type FaceEvent =
  | { type: 'stt.start' }
  | { type: 'stt.stop' }
  | { type: 'agent.status'; status: string; task?: string | null }
  | { type: 'speech.start' }
  | { type: 'speech.sentence'; text: string }
  | { type: 'speech.end' }
  | { type: 'task.done' }
  | { type: 'error' }
  | { type: 'connection'; online: boolean }
  | { type: 'mood.decay' };

export interface FaceState {
  listening: boolean;
  speaking: boolean;
  thinking: boolean;
  offline: boolean;
  mood: FaceMood;
  caption: string;
}

export const initialFace: FaceState = {
  listening: false,
  speaking: false,
  thinking: false,
  offline: false,
  mood: 'neutral',
  caption: '',
};

export function activityOf(s: FaceState): FaceActivity {
  if (s.listening) return 'listening';
  if (s.speaking) return 'speaking';
  if (s.thinking) return 'thinking';
  return 'idle';
}

const BUSY = new Set(['thinking', 'working', 'delegating']);

export function reduceFace(s: FaceState, e: FaceEvent): FaceState {
  switch (e.type) {
    case 'stt.start':
      // A new turn is starting — a concerned face perks back up.
      return { ...s, listening: true, caption: 'Listening…', mood: s.mood === 'concerned' ? 'neutral' : s.mood };
    case 'stt.stop':
      return { ...s, listening: false, caption: s.thinking || s.speaking ? s.caption : '' };
    case 'agent.status': {
      const busy = BUSY.has(e.status);
      return {
        ...s,
        thinking: busy,
        caption: busy ? (e.task ?? 'Thinking…') : s.speaking ? s.caption : '',
        mood: busy && s.mood === 'concerned' ? 'neutral' : s.mood,
      };
    }
    case 'speech.start':
      return { ...s, speaking: true };
    case 'speech.sentence':
      return { ...s, caption: e.text };
    case 'speech.end':
      return { ...s, speaking: false, caption: s.thinking ? s.caption : '' };
    case 'task.done':
      return { ...s, thinking: false, mood: 'pleased' };
    case 'error':
      return { ...s, thinking: false, speaking: false, mood: 'concerned' };
    case 'connection':
      return e.online
        ? { ...s, offline: false, mood: s.mood === 'concerned' ? 'neutral' : s.mood }
        : { ...s, offline: true, mood: 'concerned' };
    case 'mood.decay':
      return s.mood === 'pleased' ? { ...s, mood: 'neutral' } : s;
  }
}

// ---------------------------------------------------------------------------
// Store wrapper — the impure shell (decay timer lives here, not in the reducer)
// ---------------------------------------------------------------------------

const PLEASED_DECAY_MS = 4000;
let decayTimer: ReturnType<typeof setTimeout> | null = null;

interface FaceStore extends FaceState {
  apply: (e: FaceEvent) => void;
}

export const useFaceStore = create<FaceStore>((set, get) => ({
  ...initialFace,

  apply(e) {
    const { listening, speaking, thinking, offline, mood, caption } = get();
    set(reduceFace({ listening, speaking, thinking, offline, mood, caption }, e));
    if (e.type === 'task.done') {
      if (decayTimer) clearTimeout(decayTimer);
      decayTimer = setTimeout(() => get().apply({ type: 'mood.decay' }), PLEASED_DECAY_MS);
    }
  },
}));
