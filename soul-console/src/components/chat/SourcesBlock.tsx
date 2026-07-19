import { useState } from 'react';
import type { DelegationRecord } from '../../api/types';

/**
 * The evidence behind an answer (docs/researcher-agent.md §7.4). Collapsed to a single
 * "94% · 3 sources" line; expanded it lists the pages actually read, so the user can
 * judge the answer for themselves instead of taking SOUL's word for it.
 */
export function SourcesBlock({ delegations }: { delegations: DelegationRecord[] }) {
  const [open, setOpen] = useState(false);

  // The last attempt is the one that produced the answer — a low-confidence first try
  // was already retried, and its sources are not what SOUL ended up saying.
  const final = [...delegations].reverse().find((d) => d.status === 'completed');
  const sources = final?.sources ?? [];
  if (!final || sources.length === 0) return null;

  const confidence = final.confidence != null ? `${Math.round(final.confidence * 100)}%` : null;

  return (
    <div className="mt-2.5 border-t border-line pt-2">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 font-mono text-[10px] text-muted transition-colors hover:text-accent-dim"
        aria-expanded={open}
      >
        <span className={open ? 'rotate-90 transition-transform' : 'transition-transform'}>›</span>
        {confidence && <span>{confidence} ·</span>}
        <span>
          {sources.length} source{sources.length === 1 ? '' : 's'}
        </span>
      </button>
      {open && (
        <ul className="mt-1.5 space-y-1">
          {sources.map((source) => (
            <li key={source.url} className="truncate text-[11px]">
              <a
                href={source.url}
                target="_blank"
                rel="noreferrer noopener"
                className="text-accent-dim hover:underline"
                title={source.url}
              >
                {source.title || source.url}
              </a>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
