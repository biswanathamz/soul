import { useEffect, useState } from 'react';
import { cn } from '../../lib/cn';
import { formatElapsed } from '../../lib/time';
import type { AgentView } from '../../state/agentStore';
import { StatusDot } from '../common/StatusDot';

function useElapsed(since: number | null): string | null {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!since) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [since]);
  return since ? formatElapsed(Date.now() - since) : null;
}

export function AgentCard({ agent, flash }: { agent: AgentView; flash: boolean }) {
  const elapsed = useElapsed(agent.since);
  const busy = agent.since !== null;

  return (
    <div
      className={cn(
        'rounded-lg border bg-surface p-3 transition-colors',
        busy ? 'border-accent-dim' : 'border-line',
        agent.status === 'failed' && 'border-err',
        flash && 'delegate-flash',
      )}
    >
      <div className="flex items-center gap-2">
        <StatusDot status={agent.status} />
        <span className="font-mono text-xs font-semibold uppercase tracking-widest">
          {agent.role}
        </span>
        {elapsed && <span className="ml-auto font-mono text-[10px] text-muted">{elapsed}</span>}
      </div>
      <div className="mt-1.5 truncate font-mono text-[11px] text-muted" title={agent.model}>
        ⬡ {agent.model}
      </div>
      {agent.task && (
        <div className="mt-1.5 line-clamp-2 text-xs text-text" title={agent.task}>
          {agent.task}
        </div>
      )}
      {agent.toolNote && (
        <div className="mt-1 truncate font-mono text-[10px] text-accent-dim" title={agent.toolNote}>
          ⚙ {agent.toolNote}
        </div>
      )}
    </div>
  );
}
