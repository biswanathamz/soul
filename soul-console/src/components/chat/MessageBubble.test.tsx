import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { DelegationRecord, Message } from '../../api/types';
import { MessageBubble } from './MessageBubble';

const answer = (delegations?: DelegationRecord[]): Message => ({
  id: 'm1',
  role: 'assistant',
  text: "Node.js 22 'Jod' is the current LTS.",
  createdAt: new Date().toISOString(),
  delegations,
});

const researched: DelegationRecord = {
  to: 'researcher',
  task: 'latest Node.js LTS version',
  attempt: 1,
  status: 'completed',
  confidence: 0.94,
  sources: [
    { title: 'Node.js — Previous Releases', url: 'https://nodejs.org/en/about/previous-releases' },
    { title: 'endoflife.date', url: 'https://endoflife.date/nodejs' },
  ],
};

describe('MessageBubble — visible orchestration', () => {
  it('a plain answer carries no delegation lines or sources', () => {
    render(<MessageBubble message={answer()} />);
    expect(screen.queryByText(/→/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('shows who was asked and what for', () => {
    render(<MessageBubble message={answer([researched])} />);
    expect(screen.getByText(/→ researcher: latest Node\.js LTS version/)).toBeInTheDocument();
  });

  it('summarizes the evidence, and lists it on demand', async () => {
    const user = userEvent.setup();
    render(<MessageBubble message={answer([researched])} />);

    // Collapsed: the user can see how much to trust this at a glance.
    const toggle = screen.getByRole('button', { name: /94% · 2 sources/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();

    await user.click(toggle);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute('href', 'https://nodejs.org/en/about/previous-releases');
    expect(links[0]).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('a retry reads as SOUL double-checking itself', () => {
    render(
      <MessageBubble
        message={answer([
          { ...researched, attempt: 1, confidence: 0.2, sources: [] },
          { ...researched, attempt: 2 },
        ])}
      />,
    );
    expect(screen.getByText(/→ researcher: latest Node\.js LTS version/)).toBeInTheDocument();
    expect(screen.getByText(/→ researcher: \(double-checking, other sources\)/)).toBeInTheDocument();
  });

  it('the sources shown are the ones the final answer was actually based on', async () => {
    // The first attempt was retried away — citing its sources would be a lie.
    const user = userEvent.setup();
    render(
      <MessageBubble
        message={answer([
          {
            ...researched,
            attempt: 1,
            confidence: 0.2,
            sources: [{ title: 'A dodgy blog', url: 'https://example.com/blog' }],
          },
          { ...researched, attempt: 2 },
        ])}
      />,
    );

    await user.click(screen.getByRole('button', { name: /94% · 2 sources/ }));

    expect(screen.queryByText('A dodgy blog')).not.toBeInTheDocument();
    expect(screen.getByText('endoflife.date')).toBeInTheDocument();
  });

  it('a stopped delegation says so, and offers no confidence', () => {
    render(
      <MessageBubble
        message={answer([
          { to: 'researcher', task: 'latest Node.js LTS version', attempt: 1, status: 'cancelled' },
        ])}
      />,
    );
    expect(screen.getByText(/\(stopped\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
