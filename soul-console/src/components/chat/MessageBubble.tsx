import type { DelegationRecord, Message } from '../../api/types';
import { formatClock } from '../../lib/time';
import { Markdown } from './Markdown';
import { SourcesBlock } from './SourcesBlock';

const OUTCOME: Partial<Record<NonNullable<DelegationRecord['status']>, string>> = {
  cancelled: ' (stopped)',
  failed: ' (failed)',
};

/** The time the message was sent, shown under the bubble. */
function Timestamp({ iso }: { iso: string }) {
  const clock = formatClock(iso);
  if (!clock) return null;
  return (
    <time dateTime={iso} className="px-1 font-mono text-[10px] text-muted">
      {clock}
    </time>
  );
}

/** "→ researcher: latest Node.js LTS version" — visible orchestration, in the transcript. */
function DelegationLine({ delegation }: { delegation: DelegationRecord }) {
  // A retry says what it's doing rather than repeating the task: the user sees SOUL
  // double-checking itself, which is the point of showing this at all.
  const what = delegation.attempt > 1 ? '(double-checking, other sources)' : delegation.task;
  return (
    <div className="truncate font-mono text-[10px] text-muted" title={delegation.task}>
      → {delegation.to}: {what}
      {delegation.status ? (OUTCOME[delegation.status] ?? '') : ''}
    </div>
  );
}

export function MessageBubble({ message }: { message: Message }) {
  if (message.role === 'user') {
    return (
      <div className="flex flex-col items-end gap-0.5">
        <div className="max-w-[80%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-surface2 px-4 py-2.5 text-sm leading-relaxed">
          {message.text}
        </div>
        <Timestamp iso={message.createdAt} />
      </div>
    );
  }
  const delegations = message.delegations ?? [];
  return (
    <div className="flex flex-col items-start gap-0.5">
      <div className="max-w-[85%] rounded-lg border-l-2 border-accent-dim bg-surface px-4 py-3">
        <div className="mb-1 font-mono text-[10px] font-semibold tracking-[0.25em] text-accent-dim">
          ◉ SOUL
        </div>
        {delegations.length > 0 && (
          <div className="mb-2 space-y-0.5 border-l border-line pl-2">
            {delegations.map((delegation, i) => (
              <DelegationLine key={`${delegation.to}-${delegation.attempt}-${i}`} delegation={delegation} />
            ))}
          </div>
        )}
        <Markdown text={message.text} />
        {delegations.length > 0 && <SourcesBlock delegations={delegations} />}
      </div>
      <Timestamp iso={message.createdAt} />
    </div>
  );
}
