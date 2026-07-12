import { MotionConfig } from 'framer-motion';
import { AgentRail } from './components/agents/AgentRail';
import { ChatPanel } from './components/chat/ChatPanel';
import { ErrorBanner } from './components/common/ErrorBanner';
import { Header } from './components/layout/Header';
import { SettingsDrawer } from './components/settings/SettingsDrawer';
import { cn } from './lib/cn';
import { useSettingsStore } from './state/settingsStore';

export default function App() {
  const reducedMotion = useSettingsStore((s) => s.reducedMotion);
  return (
    <MotionConfig reducedMotion={reducedMotion ? 'always' : 'user'}>
      <div className={cn('flex h-full flex-col', reducedMotion && 'reduce-motion')}>
        <Header />
        <ErrorBanner />
        <div className="flex min-h-0 flex-1">
          <ChatPanel />
          <AgentRail />
        </div>
        <SettingsDrawer />
      </div>
    </MotionConfig>
  );
}
