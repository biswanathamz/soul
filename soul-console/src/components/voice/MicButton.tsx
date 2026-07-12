import { cn } from '../../lib/cn';
import { useSettingsStore } from '../../state/settingsStore';
import { useVoiceStore } from '../../state/voiceStore';

function MicIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className={className}>
      <rect x="9" y="2" width="6" height="12" rx="3" />
      <path d="M5 10v1a7 7 0 0 0 14 0v-1M12 18v4" />
    </svg>
  );
}

export function MicButton() {
  const supported = useVoiceStore((s) => s.supported.stt);
  const micState = useVoiceStore((s) => s.micState);
  const startListening = useVoiceStore((s) => s.startListening);
  const stopListening = useVoiceStore((s) => s.stopListening);
  const mode = useSettingsStore((s) => s.voiceMode);

  if (!supported || mode === 'off') return null;
  const listening = micState === 'listening';
  const handsfree = mode === 'handsfree';

  const pttProps = handsfree
    ? { onClick: () => (listening ? stopListening() : startListening()) }
    : {
        onPointerDown: () => startListening(),
        onPointerUp: () => stopListening(),
        onPointerLeave: () => listening && stopListening(),
        onKeyDown: (e: React.KeyboardEvent) => {
          if (e.key === ' ' && !e.repeat) {
            e.preventDefault();
            startListening();
          }
        },
        onKeyUp: (e: React.KeyboardEvent) => {
          if (e.key === ' ') {
            e.preventDefault();
            stopListening();
          }
        },
      };

  return (
    <button
      type="button"
      aria-label={handsfree ? 'Toggle hands-free listening' : 'Hold to talk'}
      aria-pressed={listening}
      title={handsfree ? 'Toggle hands-free listening' : 'Hold to talk (or hold Space when focused)'}
      className={cn(
        'flex h-9 w-9 shrink-0 items-center justify-center rounded-full border transition-colors',
        listening
          ? 'border-accent bg-accent text-bg shadow-glow'
          : micState === 'error'
            ? 'border-err text-err'
            : 'border-line text-muted hover:border-accent-dim hover:text-accent',
      )}
      {...pttProps}
    >
      <MicIcon className="h-4 w-4" />
    </button>
  );
}
