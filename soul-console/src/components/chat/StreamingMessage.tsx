import { useChatStore } from '../../state/chatStore';
import { useThrottled } from '../../lib/useThrottled';
import { Markdown } from './Markdown';

export function StreamingMessage() {
  const streaming = useChatStore((s) => s.streaming);
  const text = useThrottled(streaming?.text ?? '', 33);
  if (!streaming) return null;
  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] rounded-lg border-l-2 border-accent bg-surface px-4 py-3 shadow-glow">
        <div className="mb-1 font-mono text-[10px] font-semibold tracking-[0.25em] text-accent">
          ◉ SOUL
        </div>
        <Markdown text={text} />
        <span className="stream-caret" aria-hidden />
      </div>
    </div>
  );
}
