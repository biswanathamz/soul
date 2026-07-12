import { create } from 'zustand';
import type { SocketStatus } from '../api/ws';

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
    set((s) => ({ status, everConnected: s.everConnected || status === 'open' })),
}));
