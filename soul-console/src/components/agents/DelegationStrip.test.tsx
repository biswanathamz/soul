import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentView } from '../../state/agentStore';
import { useAgentStore } from '../../state/agentStore';
import { useChatStore } from '../../state/chatStore';
import { useUiStore } from '../../state/uiStore';
import { DelegationStrip } from './DelegationStrip';

const cancelConversation = vi.hoisted(() => vi.fn());
vi.mock('../../api/rest', () => ({ cancelConversation }));

const agentInitial = useAgentStore.getState();
const chatInitial = useChatStore.getState();
const uiInitial = useUiStore.getState();

const agent = (over: Partial<AgentView> = {}): AgentView => ({
  role: 'researcher',
  model: 'llama3.1:8b',
  status: 'working',
  description: null,
  task: 'Searching the web…',
  toolNote: null,
  since: Date.now(),
  ...over,
});

beforeEach(() => {
  useAgentStore.setState(agentInitial, true);
  useChatStore.setState({ ...chatInitial, conversationId: 'conv-1' }, true);
  useUiStore.setState(uiInitial, true);
  cancelConversation.mockReset().mockResolvedValue(undefined);
});

describe('DelegationStrip', () => {
  it('is invisible until a sub-agent is actually working', () => {
    useAgentStore.setState({ agents: { researcher: agent({ status: 'idle', task: null }) } });
    render(<DelegationStrip />);
    expect(screen.queryByLabelText('Working sub-agents')).not.toBeInTheDocument();
  });

  it('shows the working agent and its live stage label', () => {
    useAgentStore.setState({ agents: { researcher: agent() } });
    render(<DelegationStrip />);

    expect(screen.getByText('researcher')).toBeInTheDocument();
    expect(screen.getByText('Searching the web…')).toBeInTheDocument();
  });

  it('never shows a card for the Manager — SOUL herself is the face', () => {
    useAgentStore.setState({
      agents: {
        super: agent({ role: 'super', task: 'Understanding request' }),
        researcher: agent(),
      },
    });
    render(<DelegationStrip />);

    expect(screen.queryByText('super')).not.toBeInTheDocument();
    expect(screen.getByText('researcher')).toBeInTheDocument();
  });

  it('stop cancels the conversation and holds "stopping…" until the worker winds down', async () => {
    useAgentStore.setState({ agents: { researcher: agent() } });
    const user = userEvent.setup();
    const { rerender } = render(<DelegationStrip />);

    await user.click(screen.getByRole('button', { name: 'Stop researcher' }));

    expect(cancelConversation).toHaveBeenCalledTimes(1);
    expect(cancelConversation).toHaveBeenCalledWith('conv-1');
    // Cancellation is cooperative — the worker is still winding down.
    expect(screen.getByRole('button', { name: 'Stopping' })).toBeDisabled();

    // task.cancelled lands → the agent goes idle → the strip animates away.
    useAgentStore.setState({ agents: { researcher: agent({ status: 'idle', task: null }) } });
    rerender(<DelegationStrip />);
    await waitFor(() =>
      expect(screen.queryByLabelText('Working sub-agents')).not.toBeInTheDocument(),
    );
  });

  it('anchors top-left regardless of the chat dock, which never overlaps it', () => {
    useAgentStore.setState({ agents: { researcher: agent() } });
    useUiStore.setState({ dockExpanded: true });
    const { container } = render(<DelegationStrip />);

    const strip = container.querySelector('[aria-label="Working sub-agents"]');
    expect(strip?.className).toContain('left-6');
    expect(strip?.className).toContain('top-6');
    // The old dock-dodge shim is gone — top-left is clear of the right-side overlay.
    expect(strip?.className).not.toContain('50%-14rem');
  });

  it('a failed cancel lets the user try again rather than hanging on "stopping…"', async () => {
    cancelConversation.mockRejectedValue(new Error('backend unreachable'));
    useAgentStore.setState({ agents: { researcher: agent() } });
    const user = userEvent.setup();
    render(<DelegationStrip />);

    await user.click(screen.getByRole('button', { name: 'Stop researcher' }));

    expect(await screen.findByRole('button', { name: 'Stop researcher' })).toBeEnabled();
  });
});
