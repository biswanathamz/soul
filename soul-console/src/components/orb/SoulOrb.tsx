import { cn } from '../../lib/cn';
import { useChatStore } from '../../state/chatStore';
import { useVoiceStore } from '../../state/voiceStore';

export type OrbState = 'idle' | 'listening' | 'thinking' | 'speaking';

/** Priority when signals overlap: listening > speaking > thinking > idle (TDD §7.3). */
export function useOrbState(): OrbState {
  const listening = useVoiceStore((s) => s.micState === 'listening');
  const speaking = useVoiceStore((s) => s.speaking);
  const thinking = useChatStore((s) => s.sending);
  if (listening) return 'listening';
  if (speaking) return 'speaking';
  if (thinking) return 'thinking';
  return 'idle';
}

export function SoulOrb({ size = 32, className }: { size?: number; className?: string }) {
  const state = useOrbState();
  return (
    <div
      className={cn('orb', className)}
      data-state={state}
      style={{ width: size, height: size }}
      aria-hidden
    >
      <div className="orb-ring" />
      <div className="orb-core" />
    </div>
  );
}
