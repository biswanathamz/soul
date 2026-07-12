import { create } from 'zustand';

interface UiState {
  settingsOpen: boolean;
  railOpen: boolean;
  setSettingsOpen: (open: boolean) => void;
  toggleRail: () => void;
}

export const useUiStore = create<UiState>((set) => ({
  settingsOpen: false,
  railOpen: false,
  setSettingsOpen: (settingsOpen) => set({ settingsOpen }),
  toggleRail: () => set((s) => ({ railOpen: !s.railOpen })),
}));
