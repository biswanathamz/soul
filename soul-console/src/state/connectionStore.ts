import { create } from 'zustand';
import type { SocketStatus } from '../api/ws';
import { useFaceStore } from './faceStore';

interface ConnectionState {
  status: SocketStatus;
  /** Distinguishes "still booting" from "was connected, lost it". */
  everConnected: boolean;
  setStatus: (status: SocketStatus) => void;
}

export const useConnectionStore = create<ConnectionState>((set) => ({
  status: 'connecting',
  everConnected: false,
  setStatus: (status) =>
    set((s) => {
      // Losing an established stream worries the face; reconnecting calms it.
      if (s.everConnected && status !== 'open' && s.status === 'open') {
        useFaceStore.getState().apply({ type: 'connection', online: false });
      } else if (status === 'open' && s.status !== 'open' && s.everConnected) {
        useFaceStore.getState().apply({ type: 'connection', online: true });
      }
      return { status, everConnected: s.everConnected || status === 'open' };
    }),
}));
