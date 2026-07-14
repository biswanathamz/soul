import { AnimatePresence, motion } from 'framer-motion';
import { useEffect } from 'react';
import { cn } from '../../lib/cn';
import { useChatStore } from '../../state/chatStore';
import { useUiStore } from '../../state/uiStore';
import { ChatPanel } from './ChatPanel';
import { Composer } from './Composer';

/**
 * The chat, demoted from main event to dock (docs/voice-and-face.md §3):
 * collapsed = a compact bar (composer + latest snippet + unread badge),
 * expanded = the full transcript panel. ⌘/Ctrl+K toggles.
 */
export function ChatDock() {
  const expanded = useUiStore((s) => s.dockExpanded);
  const setExpanded = useUiStore((s) => s.setDockExpanded);
  const toggleDock = useUiStore((s) => s.toggleDock);
  const unread = useUiStore((s) => s.dockUnread);
  const last = useChatStore((s) => s.messages[s.messages.length - 1]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        toggleDock();
      }
      if (e.key === 'Escape' && useUiStore.getState().dockExpanded) setExpanded(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [toggleDock, setExpanded]);

  return (
    <>
      <AnimatePresence>
        {expanded && (
          <motion.aside
            aria-label="Chat"
            className="absolute inset-y-0 right-0 z-30 flex w-full max-w-md flex-col border-l border-line bg-surface shadow-2xl"
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'tween', duration: 0.2 }}
          >
            <div className="flex h-10 shrink-0 items-center justify-between border-b border-line px-3">
              <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.25em] text-muted">
                Chat
              </span>
              <button
                onClick={() => setExpanded(false)}
                aria-label="Collapse chat"
                title="Collapse chat (Esc)"
                className="flex h-7 w-7 items-center justify-center rounded-lg border border-line text-muted hover:border-accent-dim hover:text-text"
              >
                ⌄
              </button>
            </div>
            <ChatPanel />
          </motion.aside>
        )}
      </AnimatePresence>

      {!expanded && (
        <div className="absolute bottom-4 right-4 z-30 w-full max-w-sm">
          <button
            type="button"
            onClick={() => setExpanded(true)}
            aria-label={`Expand chat${unread > 0 ? ` (${unread} unread)` : ''}`}
            title="Expand chat (⌘K)"
            className="mb-1.5 flex w-full items-center gap-2 rounded-xl border border-line bg-surface/90 px-3 py-2 text-left backdrop-blur transition-colors hover:border-accent-dim"
          >
            <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.25em] text-muted">
              Chat
            </span>
            {unread > 0 && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-accent px-1.5 font-mono text-[10px] font-bold text-black">
                {unread}
              </span>
            )}
            <span className={cn('min-w-0 flex-1 truncate text-xs', last ? 'text-muted' : 'text-muted/60')}>
              {last ? last.text : 'No messages yet'}
            </span>
            <span aria-hidden className="text-muted">
              ⌃
            </span>
          </button>
          <div className="rounded-xl border border-line bg-surface/90 backdrop-blur [&>div]:border-t-0 [&>div]:bg-transparent [&>div]:px-2 [&>div]:py-1.5">
            <Composer />
          </div>
        </div>
      )}
    </>
  );
}
