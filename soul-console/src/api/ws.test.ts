import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WsEvent } from './types';
import {
  BASE_DELAY_MS,
  MAX_DELAY_MS,
  ReconnectingSocket,
  backoffDelay,
  type SocketStatus,
  type WsLike,
} from './ws';

class FakeWs implements WsLike {
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  closeCalled = false;
  close() {
    this.closeCalled = true;
    this.onclose?.();
  }
}

describe('backoffDelay', () => {
  it('stays within jittered bounds and caps at MAX_DELAY_MS', () => {
    expect(backoffDelay(0, () => 0)).toBeCloseTo(BASE_DELAY_MS * 0.8, 6);
    expect(backoffDelay(0, () => 1)).toBeCloseTo(BASE_DELAY_MS * 1.2, 6);
    for (let attempt = 0; attempt < 12; attempt++) {
      const delay = backoffDelay(attempt);
      expect(delay).toBeGreaterThanOrEqual(BASE_DELAY_MS * 0.8);
      expect(delay).toBeLessThanOrEqual(MAX_DELAY_MS);
    }
  });

  it('grows with the attempt number', () => {
    expect(backoffDelay(3, () => 0.5)).toBeGreaterThan(backoffDelay(0, () => 0.5));
  });
});

describe('ReconnectingSocket', () => {
  const instances: FakeWs[] = [];
  const events: WsEvent[] = [];
  const statuses: SocketStatus[] = [];

  const makeSocket = () =>
    new ReconnectingSocket('ws://test/ws/stream', {
      onEvent: (e) => events.push(e),
      onStatus: (s) => statuses.push(s),
      wsFactory: () => {
        const ws = new FakeWs();
        instances.push(ws);
        return ws;
      },
    });

  beforeEach(() => {
    vi.useFakeTimers();
    instances.length = 0;
    events.length = 0;
    statuses.length = 0;
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('reports connecting → open and dispatches parsed events', () => {
    const socket = makeSocket();
    socket.connect();
    instances[0].onopen?.();
    expect(statuses).toEqual(['connecting', 'open']);

    const evt: WsEvent = { type: 'token', payload: { messageId: 'm1', token: 'hi' } };
    instances[0].onmessage?.({ data: JSON.stringify(evt) });
    expect(events).toEqual([evt]);

    instances[0].onmessage?.({ data: 'not-json' });
    expect(events).toHaveLength(1);
    socket.close();
  });

  it('reconnects with backoff after an unexpected close', () => {
    const socket = makeSocket();
    socket.connect();
    instances[0].onopen?.();
    instances[0].onclose?.(); // server dropped us
    expect(statuses).toContain('closed');
    expect(instances).toHaveLength(1);

    vi.advanceTimersByTime(MAX_DELAY_MS);
    expect(instances).toHaveLength(2);
    socket.close();
  });

  it('does not reconnect after an intentional close', () => {
    const socket = makeSocket();
    socket.connect();
    instances[0].onopen?.();
    socket.close();
    vi.advanceTimersByTime(MAX_DELAY_MS * 2);
    expect(instances).toHaveLength(1);
  });

  it('force-reconnects after prolonged silence', () => {
    const socket = makeSocket();
    socket.connect();
    instances[0].onopen?.();
    vi.advanceTimersByTime(30001);
    expect(instances[0].closeCalled).toBe(true);
    socket.close();
  });
});
