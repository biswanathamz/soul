import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useState } from 'react';
import { getModels, rebindModel } from '../../api/rest';
import { useAgentStore } from '../../state/agentStore';
import { useConnectionStore } from '../../state/connectionStore';
import { useSettingsStore, type SttEngine, type VoiceMode } from '../../state/settingsStore';
import { useUiStore } from '../../state/uiStore';
import { useVoiceStore } from '../../state/voiceStore';
import { listSoulVoices, type VoiceInfo } from '../../voice/speaker';
import { listVoices } from '../../voice/tts';

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="mb-2 mt-6 font-mono text-[10px] font-semibold uppercase tracking-[0.25em] text-muted first:mt-0">
      {children}
    </h3>
  );
}

const VOICE_MODES: Array<{ value: VoiceMode; label: string; hint: string }> = [
  { value: 'off', label: 'Off', hint: 'Text chat only' },
  { value: 'ptt', label: 'Push to talk', hint: 'Hold the mic button to speak' },
  {
    value: 'handsfree',
    label: 'Wake word — “Hey SOUL”',
    hint: 'Listens in the background for its name',
  },
];

const STT_ENGINES: Array<{ value: SttEngine; label: string; hint: string }> = [
  {
    value: 'local',
    label: 'Local (private)',
    hint: 'Whisper on soul-voice — audio never leaves this machine',
  },
  {
    value: 'browser',
    label: 'Browser',
    hint: 'Web Speech API — cloud-backed in Chrome, fastest to respond',
  },
];

export function SettingsDrawer() {
  const open = useUiStore((s) => s.settingsOpen);
  const setOpen = useUiStore((s) => s.setSettingsOpen);
  const agents = useAgentStore((s) => s.agents);
  const setAgentModel = useAgentStore((s) => s.setModel);
  const settings = useSettingsStore();
  const supported = useVoiceStore((s) => s.supported);
  const wsStatus = useConnectionStore((s) => s.status);

  const [models, setModels] = useState<string[]>([]);
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [soulVoices, setSoulVoices] = useState<VoiceInfo[]>([]);
  const [rebindError, setRebindError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    getModels()
      .then((list) => setModels(list.map((m) => m.name)))
      .catch(() => setModels([]));
    listSoulVoices()
      .then(setSoulVoices)
      .catch(() => setSoulVoices([]));
    const refreshVoices = () => setVoices(listVoices());
    refreshVoices();
    window.speechSynthesis?.addEventListener?.('voiceschanged', refreshVoices);
    return () => window.speechSynthesis?.removeEventListener?.('voiceschanged', refreshVoices);
  }, [open]);

  const roles = Object.keys(agents).filter((r) => r !== 'super');

  const rebind = async (role: string, model: string) => {
    const previous = agents[role]?.model;
    setAgentModel(role, model); // optimistic
    setRebindError(null);
    try {
      await rebindModel(role, model);
    } catch {
      if (previous) setAgentModel(role, previous);
      setRebindError(`Could not rebind ${role} — orchestrator unreachable.`);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            className="fixed inset-0 z-40 bg-black/60"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setOpen(false)}
          />
          <motion.aside
            role="dialog"
            aria-label="Settings"
            className="fixed inset-y-0 right-0 z-50 w-96 max-w-full overflow-y-auto border-l border-line bg-surface p-5"
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'tween', duration: 0.2 }}
          >
            <div className="flex items-center justify-between">
              <h2 className="font-mono text-sm font-bold tracking-[0.25em] text-accent">
                SETTINGS
              </h2>
              <button
                onClick={() => setOpen(false)}
                aria-label="Close settings"
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-line text-muted hover:border-accent-dim hover:text-text"
              >
                ✕
              </button>
            </div>

            <SectionTitle>Voice</SectionTitle>
            {!supported.stt && (
              <p className="mb-2 text-xs text-muted">
                Speech recognition isn't available in this browser (try Chrome/Edge). Replies can
                still be spoken aloud{supported.tts ? '' : ' — but speech synthesis is also unavailable'}.
              </p>
            )}
            <div className="space-y-1.5">
              {VOICE_MODES.map((m) => {
                const disabled = m.value !== 'off' && !supported.stt && !supported.tts;
                return (
                  <label
                    key={m.value}
                    className={`flex cursor-pointer items-center gap-3 rounded-lg border p-2.5 text-sm ${
                      settings.voiceMode === m.value ? 'border-accent-dim bg-surface2' : 'border-line'
                    } ${disabled ? 'cursor-not-allowed opacity-50' : ''}`}
                  >
                    <input
                      type="radio"
                      name="voiceMode"
                      className="accent-[color:var(--accent)]"
                      checked={settings.voiceMode === m.value}
                      disabled={disabled}
                      onChange={() => settings.setVoiceMode(m.value)}
                    />
                    <span>
                      {m.label}
                      <span className="block text-xs text-muted">{m.hint}</span>
                    </span>
                  </label>
                );
              })}
            </div>
            <div className="mt-4">
              <span className="mb-1.5 block text-xs text-muted">Speech recognition</span>
              <div className="space-y-1.5">
                {STT_ENGINES.map((e) => (
                  <label
                    key={e.value}
                    className={`flex cursor-pointer items-center gap-3 rounded-lg border p-2.5 text-sm ${
                      settings.sttEngine === e.value ? 'border-accent-dim bg-surface2' : 'border-line'
                    }`}
                  >
                    <input
                      type="radio"
                      name="sttEngine"
                      className="accent-[color:var(--accent)]"
                      checked={settings.sttEngine === e.value}
                      onChange={() => settings.setSttEngine(e.value)}
                    />
                    <span>
                      {e.label}
                      <span className="block text-xs text-muted">{e.hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </div>
            <label className="mt-3 flex cursor-pointer items-center gap-3 rounded-lg border border-line p-2.5 text-sm">
              <input
                type="checkbox"
                className="accent-[color:var(--accent)]"
                checked={settings.bargeIn}
                onChange={(e) => settings.setBargeIn(e.target.checked)}
              />
              <span>
                Barge-in
                <span className="block text-xs text-muted">
                  Say “Hey SOUL” while she's speaking to interrupt (local engine + wake word)
                </span>
              </span>
            </label>
            <label className="mt-1.5 flex cursor-pointer items-center gap-3 rounded-lg border border-line p-2.5 text-sm">
              <input
                type="checkbox"
                className="accent-[color:var(--accent)]"
                checked={settings.clapWake}
                onChange={(e) => settings.setClapWake(e.target.checked)}
              />
              <span>
                Triple clap 👏👏👏
                <span className="block text-xs text-muted">
                  Clap three times to wake SOUL without speaking (local engine + wake word)
                </span>
              </span>
            </label>
            {soulVoices.length > 0 && (
              <div className="mt-3">
                <label className="mb-1 block text-xs text-muted" htmlFor="soul-voice">
                  SOUL voice (neural)
                </label>
                <select
                  id="soul-voice"
                  className="w-full rounded-lg border border-line bg-surface2 p-2 text-sm"
                  value={settings.soulVoiceId ?? ''}
                  onChange={(e) => settings.setSoulVoice(e.target.value || null)}
                >
                  <option value="">Default ({soulVoices.find((v) => v.default)?.id ?? 'service default'})</option>
                  {soulVoices.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.id} ({v.lang})
                    </option>
                  ))}
                </select>
              </div>
            )}
            {supported.tts && voices.length > 0 && (
              <div className="mt-3">
                <label className="mb-1 block text-xs text-muted" htmlFor="tts-voice">
                  Fallback (browser) voice
                </label>
                <select
                  id="tts-voice"
                  className="w-full rounded-lg border border-line bg-surface2 p-2 text-sm"
                  value={settings.ttsVoiceURI ?? ''}
                  onChange={(e) => settings.setTtsVoice(e.target.value || null)}
                >
                  <option value="">System default</option>
                  {voices.map((v) => (
                    <option key={v.voiceURI} value={v.voiceURI}>
                      {v.name} ({v.lang})
                    </option>
                  ))}
                </select>
              </div>
            )}

            <SectionTitle>Agent models</SectionTitle>
            {roles.length === 0 && (
              <p className="text-xs text-muted">No agents online — connect the orchestrator.</p>
            )}
            <div className="space-y-1.5">
              {roles.map((role) => {
                const agent = agents[role];
                const options = models.includes(agent.model) ? models : [agent.model, ...models];
                return (
                  <div key={role} className="flex items-center gap-2 rounded-lg border border-line p-2.5">
                    <span className="w-24 shrink-0 font-mono text-xs uppercase tracking-wider">
                      {role}
                    </span>
                    <select
                      aria-label={`Model for ${role}`}
                      className="min-w-0 flex-1 rounded-lg border border-line bg-surface2 p-1.5 font-mono text-xs"
                      value={agent.model}
                      onChange={(e) => void rebind(role, e.target.value)}
                    >
                      {options.map((m) => (
                        <option key={m} value={m}>
                          {m}
                        </option>
                      ))}
                    </select>
                  </div>
                );
              })}
            </div>
            {rebindError && <p className="mt-2 text-xs text-err">{rebindError}</p>}

            <SectionTitle>Connection</SectionTitle>
            <div className="space-y-1 rounded-lg border border-line p-3 font-mono text-xs text-muted">
              <div>
                stream: <span className={wsStatus === 'open' ? 'text-ok' : 'text-warn'}>{wsStatus}</span>
              </div>
              <div>ui: {window.location.origin}</div>
              <div>api: /api → soul-orchestrator</div>
            </div>

            <SectionTitle>Motion</SectionTitle>
            <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-line p-2.5 text-sm">
              <input
                type="checkbox"
                className="accent-[color:var(--accent)]"
                checked={settings.reducedMotion}
                onChange={(e) => settings.setReducedMotion(e.target.checked)}
              />
              <span>
                Reduce motion
                <span className="block text-xs text-muted">Disable animations and glows</span>
              </span>
            </label>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  );
}
