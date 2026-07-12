import type { WsEvent } from './types';

export type SocketStatus = 'connecting' | 'open' | 'closed';

/** Minimal surface of a WebSocket, injectable for tests. */
export interface WsLike {
  onopen: (() => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
  onmessage: ((e: { data: unknown }) => void) | null;
  close(): void;
}

export interface ReconnectingSocketOptions {
  onEvent: (evt: WsEvent) => void;
  onStatus?: (s: SocketStatus) => void;
  wsFactory?: (url: string) => WsLike;
  /** Force-reconnect if nothing (not even a ping) arrives for this long. */
  silenceTimeoutMs?: number;
}

export const BASE_DELAY_MS = 500;
export const MAX_DELAY_MS = 8000;

export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const capped = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** attempt);
  return Math.min(MAX_DELAY_MS, capped * (0.8 + 0.4 * random()));
}

export class ReconnectingSocket {
  private ws: WsLike | null = null;
  private attempt = 0;
  private closedByUser = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private silenceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly url: string,
    private readonly opts: ReconnectingSocketOptions,
  ) {}

  connect(): void {
    this.closedByUser = false;
    this.clearTimers();
    this.opts.onStatus?.('connecting');
    const factory = this.opts.wsFactory ?? ((u: string) => new WebSocket(u) as unknown as WsLike);
    let ws: WsLike;
    try {
      ws = factory(this.url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;
    ws.onopen = () => {
      this.attempt = 0;
      this.opts.onStatus?.('open');
      this.bumpSilence();
    };
    ws.onmessage = (e) => {
      this.bumpSilence();
      try {
        this.opts.onEvent(JSON.parse(String(e.data)) as WsEvent);
      } catch {
        /* non-JSON frame (heartbeat) — ignore */
      }
    };
    ws.onclose = () => {
      this.opts.onStatus?.('closed');
      if (!this.closedByUser) this.scheduleReconnect();
    };
    ws.onerror = () => {
      /* onclose always follows */
    };
  }

  close(): void {
    this.closedByUser = true;
    this.clearTimers();
    this.ws?.close();
    this.ws = null;
  }

  private bumpSilence(): void {
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    this.silenceTimer = setTimeout(() => this.ws?.close(), this.opts.silenceTimeoutMs ?? 30000);
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    const delay = backoffDelay(this.attempt++);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  private clearTimers(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    this.reconnectTimer = null;
    this.silenceTimer = null;
  }
}
