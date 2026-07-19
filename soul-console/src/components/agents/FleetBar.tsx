import { cn } from '../../lib/cn';
import { BUSY, useAgentStore, type AgentView } from '../../state/agentStore';
import { StatusDot } from '../common/StatusDot';

/** The word (or live stage) each chip shows next to its status dot. */
function statusLabel(agent: AgentView): string {
  if (BUSY.has(agent.status)) return agent.task ?? 'working…';
  if (agent.status === 'failed') return 'failed';
  return 'idle';
}

/**
 * The fleet at a glance — a persistent chip row under the header listing every sub-agent
 * (docs/researcher-agent.md §7). Each chip tracks its agent live: a dim dot when idle, a
 * pulsing dot plus the current stage label while it works. The transient top-left strip
 * still handles the active task's stop button; this row is the always-on roster.
 *
 * The Manager ("super") is absent on purpose — SOUL herself is the face, so a chip for her
 * would just be the face wearing a name badge.
 */
export function FleetBar() {
  const agents = useAgentStore((s) => s.agents);
  const fleet = Object.values(agents).filter((a) => a.role !== 'super');

  // Nothing to show until the roster has loaded (hydrated on WS connect). An empty bar
  // would just be a stray line under the header.
  if (fleet.length === 0) return null;

  return (
    <div
      className="flex h-8 shrink-0 items-center gap-2 overflow-x-auto border-b border-line px-4"
      aria-label="Agent fleet"
    >
      <span className="shrink-0 font-mono text-[10px] font-semibold uppercase tracking-[0.25em] text-muted">
        Fleet
      </span>
      {fleet.map((agent) => {
        const busy = BUSY.has(agent.status);
        return (
          <span
            key={agent.role}
            className={cn(
              'flex shrink-0 items-center gap-1.5 rounded-full border px-2.5 py-1 font-mono text-[10px] transition-colors',
              busy ? 'border-accent-dim bg-surface' : 'border-line',
            )}
            title={`${agent.role}: ${statusLabel(agent)}`}
          >
            <StatusDot status={agent.status} />
            <span className="font-semibold uppercase tracking-widest text-text">{agent.role}</span>
            <span
              className={cn('max-w-[14rem] truncate', busy ? 'text-accent-dim' : 'text-muted')}
              aria-live="polite"
            >
              {statusLabel(agent)}
            </span>
          </span>
        );
      })}
    </div>
  );
}
