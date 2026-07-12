import type { Message } from '../../api/types';
import { Markdown } from './Markdown';

export function MessageBubble({ message }: { message: Message }) {
  if (message.role === 'user') {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-surface2 px-4 py-2.5 text-sm leading-relaxed">
          {message.text}
        </div>
      </div>
    );
  }
  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] rounded-lg border-l-2 border-accent-dim bg-surface px-4 py-3">
        <div className="mb-1 font-mono text-[10px] font-semibold tracking-[0.25em] text-accent-dim">
          ◉ SOUL
        </div>
        <Markdown text={message.text} />
      </div>
    </div>
  );
}
