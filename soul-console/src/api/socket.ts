import { useAgentStore } from '../state/agentStore';
import { useChatStore } from '../state/chatStore';
import { useConnectionStore } from '../state/connectionStore';
import { dispatchWsEvent } from '../state/dispatcher';
import { ReconnectingSocket } from './ws';

let socket: ReconnectingSocket | null = null;

export function bootSocket(): void {
  if (socket) return; // HMR / StrictMode guard
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new ReconnectingSocket(`${scheme}://${location.host}/ws/stream`, {
    onEvent: dispatchWsEvent,
    onStatus: (status) => {
      useConnectionStore.getState().setStatus(status);
      if (status === 'open') {
        // Recover anything missed while disconnected via REST (TDD §6.2).
        void useAgentStore.getState().hydrate();
        void useChatStore.getState().rehydrate();
      }
    },
  });
  socket.connect();
  void useAgentStore.getState().hydrate();
}
