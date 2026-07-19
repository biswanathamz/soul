import { beforeEach, describe, expect, it } from 'vitest';
import type { DelegationPayload } from '../api/types';
import { useAgentStore } from './agentStore';

const initialState = useAgentStore.getState();

beforeEach(() => {
  useAgentStore.setState(initialState, true);
});

const delegation = (id: string, attempt = 1): DelegationPayload => ({
  id,
  from: 'super',
  to: 'researcher',
  task: 'latest Node.js LTS version',
  attempt,
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

  it('attaches a result to the delegation it belongs to', () => {
    const store = useAgentStore.getState();
    store.applyDelegation(delegation('d1'));
    store.applyDelegation(delegation('d2'));

    store.applyDelegationResult({
      id: 'd2',
      status: 'completed',
      confidence: 0.94,
      sources: [{ title: 'Node.js', url: 'https://nodejs.org' }],
    });

    const [first, second] = useAgentStore.getState().delegations;
    expect(first.confidence).toBeUndefined(); // correlated by id, not "the latest one"
    expect(second.confidence).toBe(0.94);
    expect(second.sources).toHaveLength(1);
  });

  it('takeTurn drains the turn so its delegations land on exactly one answer', () => {
    const store = useAgentStore.getState();
    store.applyDelegation(delegation('d1'));
    store.applyDelegationResult({ id: 'd1', status: 'completed', confidence: 0.9, sources: [] });

    const first = useAgentStore.getState().takeTurn();
    expect(first).toHaveLength(1);
    expect(first[0]).toMatchObject({ to: 'researcher', attempt: 1, confidence: 0.9 });

    // The next turn starts empty — a delegation is never claimed by two answers.
    expect(useAgentStore.getState().takeTurn()).toEqual([]);
    // …but the history ring buffer keeps it.
    expect(useAgentStore.getState().delegations).toHaveLength(1);
  });

  it('a retry keeps both attempts in the turn', () => {
    const store = useAgentStore.getState();
    store.applyDelegation(delegation('d1', 1));
    store.applyDelegation(delegation('d2', 2));

    expect(useAgentStore.getState().takeTurn().map((d) => d.attempt)).toEqual([1, 2]);
  });
});
