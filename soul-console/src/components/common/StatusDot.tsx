import type { AgentStatus } from '../../api/types';
import { cn } from '../../lib/cn';

const STYLES: Record<AgentStatus, string> = {
  idle: 'bg-muted',
  thinking: 'bg-warn animate-pulse',
  delegating: 'bg-warn animate-pulse',
  working: 'bg-accent animate-pulse shadow-glow',
  done: 'bg-ok',
  failed: 'bg-err',
};

export function StatusDot({ status, className }: { status: AgentStatus; className?: string }) {
  return (
    <span
      className={cn('inline-block h-2 w-2 shrink-0 rounded-full', STYLES[status], className)}
      role="img"
      aria-label={status}
    />
  );
}
