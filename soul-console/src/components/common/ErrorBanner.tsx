import { useChatStore } from '../../state/chatStore';
import { useConnectionStore } from '../../state/connectionStore';

export function ErrorBanner() {
  const status = useConnectionStore((s) => s.status);
  const everConnected = useConnectionStore((s) => s.everConnected);
  const error = useChatStore((s) => s.error);
  const dismissError = useChatStore((s) => s.dismissError);

  const disconnected = status !== 'open' && everConnected;
  if (!disconnected && !error) return null;

  return (
    <div className="pointer-events-none fixed left-1/2 top-14 z-30 -translate-x-1/2">
      {disconnected ? (
        <div className="flex items-center gap-2 rounded-full border border-warn bg-surface2 px-4 py-1.5 text-xs text-warn shadow-lg">
          <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-warn" />
          Connection lost — reconnecting…
        </div>
      ) : (
        <div className="pointer-events-auto flex items-center gap-3 rounded-full border border-err bg-surface2 px-4 py-1.5 text-xs text-err shadow-lg">
          {error}
          <button onClick={dismissError} aria-label="Dismiss error" className="text-muted hover:text-text">
            ✕
          </button>
        </div>
      )}
    </div>
  );
}
