import type {
  AgentStatusPayload,
  DelegationPayload,
  ErrorPayload,
  TaskDonePayload,
  TokenPayload,
  ToolCallPayload,
  ToolResultPayload,
  WsEvent,
} from '../api/types';
import { voiceReply } from '../voice/speaker';
import { useAgentStore } from './agentStore';
import { useChatStore } from './chatStore';
import { useFaceStore } from './faceStore';
import { useUiStore } from './uiStore';

/**
 * The single place WS events fan out to stores — the only writer allowed to
 * touch multiple stores (TDD §5, §6.3).
 */
export function dispatchWsEvent(evt: WsEvent): void {
  switch (evt.type) {
    case 'token': {
      const p = evt.payload as TokenPayload;
      useChatStore.getState().appendToken(p.messageId, p.token);
      voiceReply.onToken(p.messageId, p.token); // speak sentence-by-sentence (§4.2)
      break;
    }
    case 'task.done': {
      const p = evt.payload as TaskDonePayload;
      useChatStore.getState().commitStream(p.text);
      voiceReply.onDone(p.text);
      useFaceStore.getState().apply({ type: 'task.done' });
      useUiStore.getState().noteAssistantMessage();
      break;
    }
    case 'agent.status': {
      const p = evt.payload as AgentStatusPayload;
      if (evt.agent) useAgentStore.getState().applyStatus(evt.agent, p);
      useFaceStore.getState().apply({ type: 'agent.status', status: p.status, task: p.task });
      break;
    }
    case 'delegation': {
      useAgentStore.getState().applyDelegation(evt.payload as DelegationPayload);
      break;
    }
    case 'tool.call': {
      const p = evt.payload as ToolCallPayload;
      if (evt.agent) useAgentStore.getState().applyTool(evt.agent, `${p.tool}(${p.args ?? ''})`);
      break;
    }
    case 'tool.result': {
      const p = evt.payload as ToolResultPayload;
      if (evt.agent) useAgentStore.getState().applyTool(evt.agent, `${p.tool} ✓`);
      break;
    }
    case 'error': {
      useChatStore.getState().fail((evt.payload as ErrorPayload).message);
      voiceReply.cancel();
      useFaceStore.getState().apply({ type: 'error' });
      break;
    }
    default:
      // Unknown event types are ignored for forward compatibility (TDD §6.3).
      console.debug('[ws] unknown event type', evt);
  }
}
