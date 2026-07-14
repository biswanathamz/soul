import { create } from 'zustand';

interface UiState {
  settingsOpen: boolean;
  railOpen: boolean;
  /** Chat dock (docs/voice-and-face.md §3): collapsed pill by default. */
  dockExpanded: boolean;
  dockUnread: number;
  setSettingsOpen: (open: boolean) => void;
  toggleRail: () => void;
  setDockExpanded: (open: boolean) => void;
  toggleDock: () => void;
  /** Called by the dispatcher when an assistant reply lands. */
  noteAssistantMessage: () => void;
}

export const useUiStore = create<UiState>((set) => ({
  settingsOpen: false,
  railOpen: false,
  dockExpanded: false,
  dockUnread: 0,
  setSettingsOpen: (settingsOpen) => set({ settingsOpen }),
  toggleRail: () => set((s) => ({ railOpen: !s.railOpen })),
  setDockExpanded: (dockExpanded) => set({ dockExpanded, ...(dockExpanded ? { dockUnread: 0 } : {}) }),
  toggleDock: () => set((s) => ({ dockExpanded: !s.dockExpanded, ...(s.dockExpanded ? {} : { dockUnread: 0 }) })),
  noteAssistantMessage: () => set((s) => (s.dockExpanded ? s : { dockUnread: s.dockUnread + 1 })),
}));
