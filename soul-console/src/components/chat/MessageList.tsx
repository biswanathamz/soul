import { useChatStore } from '../../state/chatStore';
import { SoulOrb, useOrbState } from '../orb/SoulOrb';
import { MessageBubble } from './MessageBubble';
import { StreamingMessage } from './StreamingMessage';

const SUGGESTIONS = [
  'Write a Python function to compute Fibonacci numbers',
  'Research the current state of local LLMs',
  'Draft a short email to my team about project SOUL',
];

function EmptyState() {
  const send = useChatStore((s) => s.send);
  const state = useOrbState();
  const line =
    state === 'listening' ? 'Listening…' : state === 'thinking' ? 'Working on it…' : 'How can I help?';
  return (
    <div className="flex h-full flex-col items-center justify-center gap-8 px-6">
      <SoulOrb size={140} />
      <div className="text-center">
        <h1 className="font-mono text-2xl font-bold tracking-[0.4em] text-accent">SOUL</h1>
        <p className="mt-2 text-sm text-muted">{line}</p>
      </div>
      <div className="flex max-w-xl flex-wrap justify-center gap-2">
        {SUGGESTIONS.map((s) => (
          <button
            key={s}
            onClick={() => void send(s)}
            className="rounded-full border border-line px-4 py-2 text-xs text-muted transition-colors hover:border-accent-dim hover:text-text"
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  );
}

export function MessageList() {
  const messages = useChatStore((s) => s.messages);
  const hasStream = useChatStore((s) => s.streaming !== null);

  if (messages.length === 0 && !hasStream) return <EmptyState />;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-6">
      <div aria-live="polite" className="flex flex-col gap-4">
        {messages.map((m) => (
          <MessageBubble key={m.id} message={m} />
        ))}
      </div>
      <StreamingMessage />
    </div>
  );
}
