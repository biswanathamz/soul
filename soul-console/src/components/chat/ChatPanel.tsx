import { useEffect, useRef, useState } from 'react';
import { useChatStore } from '../../state/chatStore';
import { Composer } from './Composer';
import { MessageList } from './MessageList';

export function ChatPanel() {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [atBottom, setAtBottom] = useState(true);
  const messageCount = useChatStore((s) => s.messages.length);
  const streamText = useChatStore((s) => s.streaming?.text);

  useEffect(() => {
    const el = scrollRef.current;
    if (el && atBottom) el.scrollTop = el.scrollHeight;
  }, [messageCount, streamText, atBottom]);

  const jumpToLatest = () => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setAtBottom(true);
  };

  // min-h-0 on the root: without it this flex child keeps its content's full height, so the
  // inner overflow-y-auto never gets a bounded height to scroll within — the transcript just
  // pushes the composer off-screen instead of scrolling.
  return (
    <main className="relative flex min-h-0 min-w-0 flex-1 flex-col bg-bg">
      <div
        ref={scrollRef}
        className="min-h-0 flex-1 overflow-y-auto"
        onScroll={(e) => {
          const el = e.currentTarget;
          setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 48);
        }}
      >
        <MessageList />
      </div>
      {!atBottom && (
        <button
          onClick={jumpToLatest}
          className="absolute bottom-24 left-1/2 z-10 -translate-x-1/2 rounded-full border border-line bg-surface2 px-4 py-1.5 text-xs text-text shadow-lg hover:border-accent-dim"
        >
          ↓ Jump to latest
        </button>
      )}
      <Composer />
    </main>
  );
}
