import { request } from './http';
import type { AgentInfo, ChatRequest, ChatResponse, ConversationDto, ModelInfo } from './types';

export const sendChat = (body: ChatRequest) =>
  request<ChatResponse>('/api/v1/chat', { method: 'POST', body: JSON.stringify(body) });

export const getAgents = () => request<AgentInfo[]>('/api/v1/agents');

export const getModels = () => request<ModelInfo[]>('/api/v1/models');

export const getConversation = (id: string) =>
  request<ConversationDto>(`/api/v1/conversations/${encodeURIComponent(id)}`);

export const rebindModel = (role: string, model: string) =>
  request<AgentInfo>(`/api/v1/agents/${encodeURIComponent(role)}/model`, {
    method: 'PUT',
    body: JSON.stringify({ model }),
  });
