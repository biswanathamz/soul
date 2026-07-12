import { beforeEach, describe, expect, it } from 'vitest';
import type { DelegationPayload } from '../api/types';
import { useAgentStore } from './agentStore';

const initialState = useAgentStore.getState();

beforeEach(() => {
  useAgentStore.setState(initialState, true);
});

const delegation = (id: string): DelegationPayload => ({
  id,
  from: 'super',
  to: 'coder',
  instruction: 'do the thing',
});

describe('agentStore', () => {
  it('applyStatus creates an entry for an unknown agent', () => {
    useAgentStore.getState().applyStatus('coder', { status: 'working', task: 'writing code' });
    const agent = useAgentStore.getState().agents.coder;
    expect(agent.status).toBe('working');
    expect(agent.task).toBe('writing code');
    expect(agent.since).not.toBeNull();
  });

  it('keeps the busy start time across consecutive busy statuses', () => {
    useAgentStore.getState().applyStatus('coder', { status: 'thinking' });
    const first = useAgentStore.getState().agents.coder.since;
    useAgentStore.getState().applyStatus('coder', { status: 'working' });
    expect(useAgentStore.getState().agents.coder.since).toBe(first);
  });

  it('clears task, toolNote and since when the agent goes idle', () => {
    useAgentStore.getState().applyStatus('coder', { status: 'working', task: 'x' });
    useAgentStore.getState().applyTool('coder', 'web_fetch(…)');
    useAgentStore.getState().applyStatus('coder', { status: 'idle' });
    const agent = useAgentStore.getState().agents.coder;
    expect(agent.task).toBeNull();
    expect(agent.toolNote).toBeNull();
    expect(agent.since).toBeNull();
  });

  it('caps the delegation ring buffer at 20 and tracks the last one', () => {
    for (let i = 1; i <= 25; i++) useAgentStore.getState().applyDelegation(delegation(`d${i}`));
    const s = useAgentStore.getState();
    expect(s.delegations).toHaveLength(20);
    expect(s.delegations[0].id).toBe('d6');
    expect(s.lastDelegation?.id).toBe('d25');
  });

  it('applyTool is a no-op for unknown agents', () => {
    useAgentStore.getState().applyTool('ghost', 'x');
    expect(useAgentStore.getState().agents.ghost).toBeUndefined();
  });
});
