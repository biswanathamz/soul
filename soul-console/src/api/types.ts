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

export interface DelegationPayload {
  id: string;
  from: Role;
  to: Role;
  instruction: string;
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
