import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useChatStore } from './chatStore';

const initialState = useChatStore.getState();

beforeEach(() => {
  useChatStore.setState(initialState, true);
  vi.restoreAllMocks();
});

describe('chatStore', () => {
  it('appendToken starts a buffer and appends to it', () => {
    useChatStore.getState().appendToken('m1', 'Hel');
    useChatStore.getState().appendToken('m1', 'lo');
    expect(useChatStore.getState().streaming).toEqual({ messageId: 'm1', text: 'Hello' });
  });

  it('appendToken restarts the buffer on a new messageId', () => {
    useChatStore.getState().appendToken('m1', 'old');
    useChatStore.getState().appendToken('m2', 'new');
    expect(useChatStore.getState().streaming).toEqual({ messageId: 'm2', text: 'new' });
  });

  it('commitStream moves the buffer into messages and stops sending', () => {
    useChatStore.setState({ sending: true });
    useChatStore.getState().appendToken('m1', 'partial answer');
    useChatStore.getState().commitStream();
    const s = useChatStore.getState();
    expect(s.streaming).toBeNull();
    expect(s.sending).toBe(false);
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0]).toMatchObject({ id: 'm1', role: 'assistant', text: 'partial answer' });
  });

  it('commitStream prefers the authoritative final text over the buffer', () => {
    useChatStore.getState().appendToken('m1', 'partial');
    useChatStore.getState().commitStream('full final text');
    expect(useChatStore.getState().messages[0].text).toBe('full final text');
  });

  it('commitStream with no buffer and no text commits nothing', () => {
    useChatStore.setState({ sending: true });
    useChatStore.getState().commitStream();
    expect(useChatStore.getState().messages).toHaveLength(0);
    expect(useChatStore.getState().sending).toBe(false);
  });

  it('send pushes an optimistic user message and stores the conversationId', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ conversationId: 'c1', messageId: 'm1' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    await useChatStore.getState().send('  hello soul  ');
    const s = useChatStore.getState();
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0]).toMatchObject({ role: 'user', text: 'hello soul' });
    expect(s.conversationId).toBe('c1');
    expect(s.sending).toBe(true);
  });

  it('send ignores empty input', async () => {
    await useChatStore.getState().send('   ');
    expect(useChatStore.getState().messages).toHaveLength(0);
  });

  it('send failure sets the error and clears sending', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new Error('down'))));
    await useChatStore.getState().send('hello');
    const s = useChatStore.getState();
    expect(s.error).toBe('SOUL backend is unreachable');
    expect(s.sending).toBe(false);
  });

  it('fail clears any in-flight stream', () => {
    useChatStore.getState().appendToken('m1', 'partial');
    useChatStore.getState().fail('boom');
    const s = useChatStore.getState();
    expect(s.streaming).toBeNull();
    expect(s.error).toBe('boom');
  });
});
