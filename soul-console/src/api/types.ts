/**
 * DTOs mirrored from soul-orchestrator (SPEC.md §5).
 * Hand-maintained; contract fixtures in tests validate against these.
 */

export type Role = 'super' | 'coder' | 'researcher' | 'writer' | 'analyst' | 'sysops';

export type AgentStatus = 'idle' | 'thinking' | 'delegating' | 'working' | 'done' | 'failed';

export interface AgentInfo {
  role: Role;
  model: string;
  status: AgentStatus;
  description?: string;
  task?: string | null;
}

export interface ChatRequest {
  conversationId?: string;
  text: string;
}

export interface ChatResponse {
  conversationId: string;
  messageId: string;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  createdAt: string;
  /**
   * The delegations that produced this answer, attached client-side as the turn ends —
   * this is what renders the "→ researcher: …" lines and the sources block. REST never
   * sends it, so a rehydrate after reconnect drops it (the answer text survives).
   */
  delegations?: DelegationRecord[];
}

/** A delegation as it appears attached to an answer. */
export interface DelegationRecord {
  to: Role;
  task: string;
  attempt: number;
  status?: DelegationResultPayload['status'];
  confidence?: number;
  sources?: Source[];
}

export interface ConversationDto {
  id: string;
  messages: Message[];
}

export interface ModelInfo {
  name: string;
}

/* ---- WebSocket event envelope (SPEC §5.2) ---- */

export type WsEventType =
  | 'token'
  | 'agent.status'
  | 'delegation'
  | 'delegation.result'
  | 'tool.call'
  | 'tool.result'
  | 'task.done'
  | 'error';

export interface WsEvent<T = unknown> {
  type: WsEventType;
  conversationId?: string;
  agent?: Role;
  payload: T;
}

export interface TokenPayload {
  messageId: string;
  token: string;
}

export interface AgentStatusPayload {
  status: AgentStatus;
  task?: string | null;
}

/** One agent handed work to another (docs/researcher-agent.md §6). */
export interface DelegationPayload {
  /** The command id — correlates this delegation with its result. */
  id: string;
  from: Role;
  to: Role;
  task: string;
  /** 1, or 2 when the confidence policy is double-checking against other sources (§5.1). */
  attempt: number;
}

export interface Source {
  title: string;
  url: string;
}

/** How a delegation ended — what lets the answer show its evidence (§7.4). */
export interface DelegationResultPayload {
  id: string;
  status: 'completed' | 'failed' | 'cancelled';
  /** 0–1, evidence-capped by the worker. Absent unless completed. */
  confidence?: number;
  sources?: Source[];
}

export interface ToolCallPayload {
  tool: string;
  args?: string;
}

export interface ToolResultPayload {
  tool: string;
  summary?: string;
}

export interface TaskDonePayload {
  messageId: string;
  text: string;
}

export interface ErrorPayload {
  message: string;
}
