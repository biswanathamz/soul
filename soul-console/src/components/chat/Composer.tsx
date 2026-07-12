import { useLayoutEffect, useRef, useState } from 'react';
import { cn } from '../../lib/cn';
import { useChatStore } from '../../state/chatStore';
import { useVoiceStore } from '../../state/voiceStore';
import { MicButton } from '../voice/MicButton';

function SendIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M3.4 20.4 21.9 12 3.4 3.6l-.01 6.53L15 12 3.39 13.87z" />
    </svg>
  );
}

export function Composer() {
  const [draft, setDraft] = useState('');
  const sending = useChatStore((s) => s.sending);
  const send = useChatStore((s) => s.send);
  const micState = useVoiceStore((s) => s.micState);
  const interim = useVoiceStore((s) => s.interim);
  const stopSpeaking = useVoiceStore((s) => s.stopSpeaking);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const listening = micState === 'listening';
  const value = listening ? interim : draft;

  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [value]);

  const submit = () => {
    const text = draft.trim();
    if (!text || sending) return;
    stopSpeaking(); // new input interrupts SOUL mid-sentence (TDD §7.2)
    void send(text);
    setDraft('');
  };

  return (
    <div className="border-t border-line bg-bg px-4 py-3">
      <div
        className={cn(
          'mx-auto flex max-w-3xl items-end gap-2 rounded-2xl border bg-surface px-3 py-2 transition-colors',
          listening ? 'border-accent shadow-glow' : 'border-line focus-within:border-accent-dim',
        )}
      >
        <MicButton />
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          readOnly={listening}
          placeholder={listening ? 'Listening…' : 'Ask SOUL anything…'}
          aria-label="Message SOUL"
          className="max-h-40 flex-1 resize-none bg-transparent py-1.5 text-sm placeholder:text-muted focus:outline-none"
          onChange={(e) => !listening && setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
        />
        <button
          type="button"
          aria-label="Send"
          disabled={sending || !draft.trim()}
          onClick={submit}
          className={cn(
            'flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-colors',
            draft.trim() && !sending
              ? 'bg-accent text-bg hover:shadow-glow'
              : 'bg-surface2 text-muted',
          )}
        >
          <SendIcon className="h-4 w-4" />
        </button>
      </div>
      <p className="mx-auto mt-1.5 max-w-3xl px-1 font-mono text-[10px] text-muted">
        Enter to send · Shift+Enter for a new line
      </p>
    </div>
  );
}
