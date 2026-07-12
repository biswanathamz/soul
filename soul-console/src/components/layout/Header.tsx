import { cn } from '../../lib/cn';
import { useConnectionStore } from '../../state/connectionStore';
import { useSettingsStore } from '../../state/settingsStore';
import { useUiStore } from '../../state/uiStore';
import { useVoiceStore } from '../../state/voiceStore';
import { SoulOrb } from '../orb/SoulOrb';

function GearIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className={className}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}

function VoiceIcon({ off, className }: { off: boolean; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className={className}>
      <path d="M11 5 6 9H2v6h4l5 4z" />
      {off ? <path d="m23 9-6 6M17 9l6 6" /> : <path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a9 9 0 0 1 0 14" />}
    </svg>
  );
}

function ConnectionBadge() {
  const status = useConnectionStore((s) => s.status);
  const styles =
    status === 'open'
      ? { dot: 'bg-ok', label: 'online', text: 'text-ok' }
      : status === 'connecting'
        ? { dot: 'bg-warn animate-pulse', label: 'connecting', text: 'text-warn' }
        : { dot: 'bg-err', label: 'offline', text: 'text-err' };
  return (
    <span className="hidden items-center gap-1.5 font-mono text-[10px] uppercase tracking-widest sm:flex">
      <span className={cn('inline-block h-1.5 w-1.5 rounded-full', styles.dot)} />
      <span className={styles.text}>{styles.label}</span>
    </span>
  );
}

export function Header() {
  const voiceMode = useSettingsStore((s) => s.voiceMode);
  const setVoiceMode = useSettingsStore((s) => s.setVoiceMode);
  const sttSupported = useVoiceStore((s) => s.supported.stt);
  const ttsSupported = useVoiceStore((s) => s.supported.tts);
  const setSettingsOpen = useUiStore((s) => s.setSettingsOpen);
  const toggleRail = useUiStore((s) => s.toggleRail);

  const voiceAvailable = sttSupported || ttsSupported;
  const voiceOff = voiceMode === 'off';

  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-line px-4">
      <SoulOrb size={26} />
      <span className="font-mono text-sm font-bold tracking-[0.35em] text-accent">SOUL</span>
      <span className="hidden text-[11px] text-muted md:inline">
        Supervised Orchestration of Unified LLM-agents
      </span>
      <div className="ml-auto flex items-center gap-2">
        <ConnectionBadge />
        {voiceAvailable && (
          <button
            type="button"
            aria-label={voiceOff ? 'Turn voice on' : 'Turn voice off'}
            title={voiceOff ? 'Turn voice on' : 'Turn voice off'}
            onClick={() => setVoiceMode(voiceOff ? 'ptt' : 'off')}
            className={cn(
              'flex h-8 w-8 items-center justify-center rounded-lg border border-line transition-colors hover:border-accent-dim',
              voiceOff ? 'text-muted' : 'text-accent',
            )}
          >
            <VoiceIcon off={voiceOff} className="h-4 w-4" />
          </button>
        )}
        <button
          type="button"
          aria-label="Toggle agent fleet"
          title="Agent fleet"
          onClick={toggleRail}
          className="flex h-8 w-8 items-center justify-center rounded-lg border border-line font-mono text-xs text-muted transition-colors hover:border-accent-dim hover:text-accent lg:hidden"
        >
          ⬡
        </button>
        <button
          type="button"
          aria-label="Open settings"
          title="Settings"
          onClick={() => setSettingsOpen(true)}
          className="flex h-8 w-8 items-center justify-center rounded-lg border border-line text-muted transition-colors hover:border-accent-dim hover:text-accent"
        >
          <GearIcon className="h-4 w-4" />
        </button>
      </div>
    </header>
  );
}
