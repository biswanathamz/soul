import { motion } from 'framer-motion';
import { useEffect, useRef, useState } from 'react';
import { cn } from '../../lib/cn';
import { useAgentStore } from '../../state/agentStore';
import { useUiStore } from '../../state/uiStore';
import { AgentCard } from './AgentCard';

const FLASH_WINDOW_MS = 1500;

export function AgentRail() {
  const agents = useAgentStore((s) => s.agents);
  const lastDelegation = useAgentStore((s) => s.lastDelegation);
  const railOpen = useUiStore((s) => s.railOpen);
  const listRef = useRef<HTMLDivElement>(null);
  const cardRefs = useRef(new Map<string, HTMLDivElement>());
  const [flight, setFlight] = useState<{ id: string; top: number } | null>(null);

  const roles = Object.keys(agents).filter((r) => r !== 'super');

  // Delegation flight: a yellow spark travels from the rail top to the target card.
  useEffect(() => {
    if (!lastDelegation || Date.now() - lastDelegation.at > FLASH_WINDOW_MS) return;
    const card = cardRefs.current.get(lastDelegation.to);
    if (!card || !listRef.current) return;
    setFlight({ id: lastDelegation.id, top: card.offsetTop + card.offsetHeight / 2 });
  }, [lastDelegation]);

  const flashRole =
    lastDelegation && Date.now() - lastDelegation.at < FLASH_WINDOW_MS ? lastDelegation.to : null;

  return (
    <aside
      className={cn(
        'w-72 shrink-0 flex-col border-l border-line bg-bg',
        'max-lg:fixed max-lg:inset-y-0 max-lg:right-0 max-lg:z-40 max-lg:shadow-2xl max-lg:transition-transform',
        railOpen ? 'max-lg:translate-x-0' : 'max-lg:translate-x-full',
        'flex lg:static lg:translate-x-0',
      )}
      aria-label="Agent fleet"
    >
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.25em] text-muted">
          Agent fleet
        </span>
        <span className="font-mono text-[10px] text-accent-dim">{roles.length} online</span>
      </div>
      <div ref={listRef} className="relative flex-1 space-y-2 overflow-y-auto p-3">
        {flight && (
          <motion.span
            key={flight.id}
            className="absolute left-1/2 z-10 h-2 w-2 -translate-x-1/2 rounded-full bg-accent shadow-glow"
            initial={{ top: 4, opacity: 0 }}
            animate={{ top: flight.top, opacity: [0, 1, 1, 0] }}
            transition={{ duration: 0.6, ease: 'easeIn' }}
            onAnimationComplete={() => setFlight(null)}
          />
        )}
        {roles.length === 0 && (
          <p className="px-1 py-4 text-center text-xs text-muted">
            No agents online — waiting for the orchestrator…
          </p>
        )}
        {roles.map((role) => (
          <div
            key={role}
            ref={(el) => {
              if (el) cardRefs.current.set(role, el);
              else cardRefs.current.delete(role);
            }}
          >
            <AgentCard agent={agents[role]} flash={flashRole === role} />
          </div>
        ))}
      </div>
    </aside>
  );
}
