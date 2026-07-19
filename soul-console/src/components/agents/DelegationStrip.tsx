import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useState } from 'react';
import { cancelConversation } from '../../api/rest';
import { formatElapsed } from '../../lib/time';
import { BUSY, useAgentStore } from '../../state/agentStore';
import { useChatStore } from '../../state/chatStore';
import { StatusDot } from '../common/StatusDot';

/**
 * Who's working, right now (docs/researcher-agent.md §7.1). While any sub-agent is busy a
 * compact strip appears under the face, ticking through that agent's live stage labels —
 * "Searching the web…", "Reading nodejs.org (1/3)", "Summarizing findings…" — so the user
 * can see the fleet working rather than watching a spinner.
 *
 * The Manager is deliberately absent: SOUL herself is the face, so a card for "super"
 * would just be the face wearing a name badge.
 */
/** Re-render once a second so the elapsed timer moves between stage events. */
function useTick(active: boolean) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [active]);
}

export function DelegationStrip() {
  const agents = useAgentStore((s) => s.agents);
  const conversationId = useChatStore((s) => s.conversationId);
  const [stopping, setStopping] = useState(false);

  const working = Object.values(agents).filter((a) => a.role !== 'super' && BUSY.has(a.status));
  const busy = working.length > 0;
  useTick(busy);

  // Cancellation is cooperative: hold "stopping…" until the worker actually winds down.
  useEffect(() => {
    if (!busy) setStopping(false);
  }, [busy]);

  const stop = async () => {
    if (!conversationId) return;
    setStopping(true);
    try {
      await cancelConversation(conversationId);
    } catch {
      setStopping(false); // the request never landed — let them try again
    }
  };

  return (
    <AnimatePresence>
      {busy && (
        <motion.div
          // Top-left of the stage — the "workshop" corner. It never overlaps the chat
          // dock, which is a right-anchored overlay, so there is nothing to dodge.
          className="pointer-events-auto absolute left-6 top-6 z-20 flex flex-col gap-2"
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={{ duration: 0.2 }}
          aria-label="Working sub-agents"
        >
          {working.map((agent) => (
            <div
              key={agent.role}
              className="delegate-flash flex items-center gap-3 rounded-full border border-accent-dim bg-surface/90 py-2 pl-4 pr-2 shadow-glow backdrop-blur"
            >
              <StatusDot status={agent.status} />
              <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.2em] text-accent-dim">
                {agent.role}
              </span>
              <span className="max-w-[22rem] truncate text-xs text-text" aria-live="polite">
                {agent.task ?? 'Working…'}
              </span>
              {agent.since && (
                <span className="font-mono text-[10px] text-muted">
                  {formatElapsed(Date.now() - agent.since)}
                </span>
              )}
              <button
                type="button"
                onClick={stop}
                disabled={stopping || !conversationId}
                className="ml-1 rounded-full px-2.5 py-1 font-mono text-[10px] text-muted transition-colors hover:bg-surface2 hover:text-err disabled:cursor-default disabled:opacity-60 disabled:hover:bg-transparent disabled:hover:text-muted"
                aria-label={stopping ? 'Stopping' : `Stop ${agent.role}`}
              >
                {stopping ? 'stopping…' : '⏹ stop'}
              </button>
            </div>
          ))}
        </motion.div>
      )}
    </AnimatePresence>
  );
}
