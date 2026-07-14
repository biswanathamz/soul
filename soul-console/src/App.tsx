import { MotionConfig } from 'framer-motion';
import { ChatDock } from './components/chat/ChatDock';
import { ErrorBanner } from './components/common/ErrorBanner';
import { SoulFace } from './components/face/SoulFace';
import { Header } from './components/layout/Header';
import { SettingsDrawer } from './components/settings/SettingsDrawer';
import { cn } from './lib/cn';
import { useSettingsStore } from './state/settingsStore';

/**
 * Face-first layout (docs/voice-and-face.md §3): SOUL's face at center stage,
 * the chat demoted to a collapsible dock. Not a chatbot — a presence.
 */
export default function App() {
  const reducedMotion = useSettingsStore((s) => s.reducedMotion);
  return (
    <MotionConfig reducedMotion={reducedMotion ? 'always' : 'user'}>
      <div className={cn('flex h-full flex-col', reducedMotion && 'reduce-motion')}>
        <Header />
        <ErrorBanner />
        <main className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden">
          <SoulFace />
          <ChatDock />
        </main>
        <SettingsDrawer />
      </div>
    </MotionConfig>
  );
}
