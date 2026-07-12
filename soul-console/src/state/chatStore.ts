import { create } from 'zustand';
import { getConversation, sendChat } from '../api/rest';
import type { Message } from '../api/types';

interface StreamBuffer {
  messageId: string;
  text: string;
}

interface ChatState {
  conversationId: string | null;
  messages: Message[];
  /** Live token buffer — kept out of messages[] so the list doesn't re-render per token. */
  streaming: StreamBuffer | null;
  sending: boolean;
  error: string | null;
  send: (text: string) => Promise<void>;
  appendToken: (messageId: string, token: string) => void;
  commitStream: (finalText?: string) => void;
  fail: (message: string) => void;
  dismissError: () => void;
  rehydrate: () => Promise<void>;
}

export const useChatStore = create<ChatState>((set, get) => ({
  conversationId: null,
  messages: [],
  streaming: null,
  sending: false,
  error: null,

  async send(text) {
    const trimmed = text.trim();
    if (!trimmed || get().sending) return;
    const userMessage: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      text: trimmed,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, userMessage], sending: true, error: null }));
    try {
      const res = await sendChat({ conversationId: get().conversationId ?? undefined, text: trimmed });
      set({ conversationId: res.conversationId });
    } catch (e) {
      get().fail(e instanceof Error ? e.message : 'Failed to reach SOUL');
    }
  },

  appendToken(messageId, token) {
    set((s) => {
      if (s.streaming && s.streaming.messageId === messageId) {
        return { streaming: { messageId, text: s.streaming.text + token } };
      }
      return { streaming: { messageId, text: token } };
    });
  },

  commitStream(finalText) {
    set((s) => {
      const text = finalText ?? s.streaming?.text ?? '';
      if (!text) return { streaming: null, sending: false };
      const message: Message = {
        id: s.streaming?.messageId ?? crypto.randomUUID(),
        role: 'assistant',
        text,
        createdAt: new Date().toISOString(),
      };
      return { messages: [...s.messages, message], streaming: null, sending: false };
    });
  },

  fail(message) {
    set({ error: message, sending: false, streaming: null });
  },

  dismissError() {
    set({ error: null });
  },

  /** After a WS reconnect: recover anything missed via REST (TDD §6.2). */
  async rehydrate() {
    const id = get().conversationId;
    if (!id || get().streaming) return;
    try {
      const conversation = await getConversation(id);
      set({ messages: conversation.messages });
    } catch {
      /* keep local copy */
    }
  },
}));
