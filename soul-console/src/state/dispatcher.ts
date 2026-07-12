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
import { stripMarkdownForSpeech } from '../voice/tts';
import { useAgentStore } from './agentStore';
import { useChatStore } from './chatStore';
import { useVoiceStore } from './voiceStore';

/**
 * The single place WS events fan out to stores — the only writer allowed to
 * touch multiple stores (TDD §5, §6.3).
 */
export function dispatchWsEvent(evt: WsEvent): void {
  switch (evt.type) {
    case 'token': {
      const p = evt.payload as TokenPayload;
      useChatStore.getState().appendToken(p.messageId, p.token);
      break;
    }
    case 'task.done': {
      const p = evt.payload as TaskDonePayload;
      useChatStore.getState().commitStream(p.text);
      useVoiceStore.getState().speak(stripMarkdownForSpeech(p.text));
      break;
    }
    case 'agent.status': {
      if (evt.agent) useAgentStore.getState().applyStatus(evt.agent, evt.payload as AgentStatusPayload);
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
      break;
    }
    default:
      // Unknown event types are ignored for forward compatibility (TDD §6.3).
      console.debug('[ws] unknown event type', evt);
  }
}
