import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { AgentView } from '../../state/agentStore';
import { useAgentStore } from '../../state/agentStore';
import { FleetBar } from './FleetBar';

const initial = useAgentStore.getState();

const agent = (over: Partial<AgentView> & { role: string }): AgentView => ({
  model: 'llama3.1:8b',
  status: 'idle',
  description: null,
  task: null,
  toolNote: null,
  since: null,
  ...over,
});

const setAgents = (...list: AgentView[]) =>
  useAgentStore.setState({ agents: Object.fromEntries(list.map((a) => [a.role, a])) });

beforeEach(() => {
  useAgentStore.setState(initial, true);
});

describe('FleetBar', () => {
  it('renders nothing before the roster has loaded', () => {
    const { container } = render(<FleetBar />);
    expect(container.querySelector('[aria-label="Agent fleet"]')).not.toBeInTheDocument();
  });

  it('lists every sub-agent, idle by default', () => {
    setAgents(agent({ role: 'researcher' }), agent({ role: 'coder' }));
    render(<FleetBar />);

    expect(screen.getByText('researcher')).toBeInTheDocument();
    expect(screen.getByText('coder')).toBeInTheDocument();
    expect(screen.getAllByText('idle')).toHaveLength(2);
  });

  it('never shows the Manager — SOUL herself is the face', () => {
    setAgents(agent({ role: 'super', status: 'thinking' }), agent({ role: 'researcher' }));
    render(<FleetBar />);

    expect(screen.queryByText('super')).not.toBeInTheDocument();
    expect(screen.getByText('researcher')).toBeInTheDocument();
  });

  it("reflects a working agent's live stage, and its dot goes active", () => {
    setAgents(agent({ role: 'researcher', status: 'working', task: 'Searching the web…', since: Date.now() }));
    render(<FleetBar />);

    expect(screen.getByText('Searching the web…')).toBeInTheDocument();
    // StatusDot exposes the status as its accessible label.
    expect(screen.getByRole('img', { name: 'working' })).toBeInTheDocument();
  });

  it('shows a generic working label when a busy agent has no stage yet', () => {
    setAgents(agent({ role: 'researcher', status: 'working', task: null }));
    render(<FleetBar />);
    expect(screen.getByText('working…')).toBeInTheDocument();
  });

  it('surfaces a failed agent as failed', () => {
    setAgents(agent({ role: 'researcher', status: 'failed' }));
    render(<FleetBar />);
    expect(screen.getByText('failed')).toBeInTheDocument();
  });
});
