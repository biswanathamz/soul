# TDD — `soul-console` (UI Microservice)

Technical Design Document for the SOUL web interface.

| | |
|---|---|
| **Version** | 0.1 (Draft) |
| **Date** | 2026-07-12 |
| **Status** | Proposed |
| **Parent doc** | [SPEC.md](SPEC.md) — system spec |
| **Service** | `soul-console/` · React 18 + TypeScript + Vite · port **7787** |

---

## 1. Purpose & Scope

`soul-console` is the single user-facing surface of SOUL: a JARVIS-style chat + voice
interface with a live view of the agent fleet. This document covers the UI service only —
component architecture, state, backend contracts, theming, voice, testing, and build. Backend
behavior is defined in [SPEC.md](SPEC.md) §5.

**In scope (v1):** chat with streamed replies, browser voice (STT/TTS), agent activity rail,
SOUL orb, settings drawer, yellow/black design system.
**Out of scope (v1):** auth, mobile apps, local Whisper/Piper voice (v2), multi-conversation
sidebar (single active conversation in v1; history API exists for later).

---

## 2. Tech Stack

| Concern | Choice | Rationale |
|---|---|---|
| Framework | React 18 + TypeScript (strict) | Spec requirement; strict TS catches contract drift with backend |
| Build | Vite 5 | Fast dev server, trivial port config (7787) |
| Styling | Tailwind CSS 3 + CSS variables for design tokens | Utility speed + themeable tokens in one place |
| State | Zustand | Minimal boilerplate; slices map cleanly to WS event streams |
| Data fetching | Native `fetch` wrapper (no react-query in v1) | Only ~5 REST endpoints; WS carries the hot path |
| Realtime | Native `WebSocket` + custom reconnecting client | No lib needed; full control over event envelope |
| Markdown | `react-markdown` + `remark-gfm` | Streamed assistant replies with tables/lists |
| Code highlight | `shiki` (lazy-loaded) | Best-quality highlighting; loaded only when a code block appears |
| Voice | Web Speech API (`SpeechRecognition`, `speechSynthesis`) | v1 zero-install voice per spec §4 |
| Animation | CSS transitions + `framer-motion` (orb & delegation flights only) | Keep bundle lean; motion where it earns its place |
| Testing | Vitest + React Testing Library + Playwright (smoke) | See §9 |
| Lint/format | ESLint (typescript-eslint) + Prettier | Standard |

Node 20+, npm. No SSR — pure SPA served statically in production.

---

## 3. Directory Structure

```
soul-console/
├── index.html
├── vite.config.ts               # port 7787, /api + /ws proxy → :7788
├── tailwind.config.ts           # yellow/black tokens
├── src/
│   ├── main.tsx
│   ├── App.tsx                  # layout: ChatPanel | AgentRail, header, drawer mount
│   ├── theme/
│   │   └── tokens.css           # CSS custom properties (single source of color truth)
│   ├── api/
│   │   ├── http.ts              # fetch wrapper, error normalization
│   │   ├── rest.ts              # typed endpoint functions
│   │   ├── ws.ts                # ReconnectingSocket: connect/backoff/dispatch
│   │   └── types.ts             # DTOs mirrored from backend (single file, hand-written)
│   ├── state/
│   │   ├── chatStore.ts         # messages, streaming buffer, send()
│   │   ├── agentStore.ts        # agent roster, live statuses, delegations
│   │   ├── voiceStore.ts        # mic state, TTS state, transcripts
│   │   └── settingsStore.ts     # persisted UI settings (localStorage)
│   ├── voice/
│   │   ├── stt.ts               # SpeechRecognition wrapper (feature-detected)
│   │   └── tts.ts               # speechSynthesis wrapper, queue, cancel-on-new-input
│   ├── components/
│   │   ├── chat/                # ChatPanel, MessageList, MessageBubble,
│   │   │                        #   StreamingMessage, Composer, CodeBlock
│   │   ├── agents/              # AgentRail, AgentCard, DelegationFlight
│   │   ├── orb/                 # SoulOrb (canvas/CSS state machine)
│   │   ├── voice/               # MicButton, VoiceLevelMeter, TtsToggle
│   │   ├── settings/            # SettingsDrawer, ModelBindingRow
│   │   └── common/              # Panel, GlowBadge, StatusDot, ErrorBanner
│   └── lib/                     # small utils (cn, time, uuid)
└── tests/
    ├── unit/                    # co-located *.test.ts(x) also allowed
    └── e2e/                     # Playwright smoke specs
```

Rule: components never call `fetch`/`WebSocket` directly — they read stores; stores call `api/`.

---

## 4. Layout & Components

### 4.1 App layout

```
┌────────────────────────────────────────────────────────────────┐
│  ⬤ SOUL          status: online          [voice ⏻] [settings ⚙]│  Header (48px)
├──────────────────────────────────────────────┬─────────────────┤
│                                              │  AGENT RAIL     │
│                 CHAT PANEL                   │  ┌───────────┐  │
│                                              │  │ ◉ coder   │  │
│   [SoulOrb — centered when conversation      │  │ qwen2.5.. │  │
│    is empty; docks to header when active]    │  │ working…  │  │
│                                              │  └───────────┘  │
│   message bubbles, streamed markdown         │  ┌───────────┐  │
│                                              │  │ ○ writer  │  │
│                                              │  └───────────┘  │
├──────────────────────────────────────────────┤     …           │
│  [🎙️]  Type or hold to speak…        [send ▸]│                 │
└──────────────────────────────────────────────┴─────────────────┘
```

- Desktop-first. Below `lg` breakpoint the agent rail collapses into a slide-over toggled
  from the header.

### 4.2 Component contracts (key ones)

| Component | Props (essence) | Behavior |
|---|---|---|
| `ChatPanel` | — | Composes MessageList + Composer; autoscroll pinned to bottom unless user scrolled up (then "jump to latest" chip) |
| `StreamingMessage` | `messageId` | Renders the live token buffer from `chatStore`; markdown re-parsed at ~30fps max (throttled), caret glow while streaming |
| `Composer` | — | Textarea (auto-grow, Enter=send, Shift+Enter=newline), MicButton, disabled while `chatStore.sending` |
| `AgentCard` | `role` | Reads `agentStore.agents[role]`: status dot (idle/thinking/working/done/failed), bound model, current task one-liner, elapsed timer while working |
| `DelegationFlight` | `delegation` | Animated yellow spark from orb/header to the target AgentCard on `delegation` events; pure decoration — never blocks data flow |
| `SoulOrb` | `state` | State machine: `idle → listening → thinking → speaking`; driven by voiceStore + chatStore (see §7.3) |
| `SettingsDrawer` | — | Voice on/off + voice selection, per-agent model rebind (`PUT /agents/{role}/model`), Ollama endpoint display, connection status |
| `ErrorBanner` | — | Surfaces WS disconnect ("reconnecting…") and REST failures; never a blank screen |

---

## 5. State Management (Zustand)

Four stores, no cross-imports between stores — cross-cutting updates flow through the WS
dispatcher (§6.3), which is the only writer allowed to touch multiple stores.

### 5.1 `chatStore`

```ts
{
  conversationId: string | null,
  messages: Message[],                    // committed messages
  streaming: { messageId, text } | null,  // live token buffer (kept OUT of messages[]
                                          //   to avoid re-rendering the whole list)
  sending: boolean,
  send(text: string): Promise<void>,      // POST /chat, optimistic user bubble
  _appendToken(t), _commitStream(), _fail(err)
}
```

### 5.2 `agentStore`

```ts
{
  agents: Record<Role, { status, model, currentTask?, since? }>,
  delegations: Delegation[],              // ring buffer (last 20) for the flight animation
  hydrate(): Promise<void>,               // GET /agents on boot + on WS reconnect
  _applyStatus(evt), _applyDelegation(evt)
}
```

### 5.3 `voiceStore`

```ts
{
  supported: { stt: boolean, tts: boolean },   // feature detection at boot
  mode: 'off' | 'ptt' | 'handsfree',
  micState: 'idle' | 'listening' | 'error',
  interimTranscript: string,                    // shown live in Composer
  speaking: boolean,
  startListening(), stopListening(), speak(text), stopSpeaking()
}
```

### 5.4 `settingsStore`

Persisted to `localStorage` (`soul.settings.v1`): TTS voice URI, voice mode, rail collapsed,
reduced-motion override.

---

## 6. Backend Integration

### 6.1 Endpoints consumed (from SPEC §5)

| Call | Used by |
|---|---|
| `POST /api/v1/chat` | `chatStore.send` |
| `GET /api/v1/agents` | `agentStore.hydrate` |
| `GET /api/v1/models` | SettingsDrawer model picker |
| `PUT /api/v1/agents/{role}/model` | SettingsDrawer rebind |
| `GET /api/v1/conversations/{id}` | rehydrate after reload (v1: latest conversation) |
| `WS /ws/stream` | everything live |

Dev: Vite proxies `/api` and `/ws` to `http://localhost:7788` — no CORS in dev, and the
browser only ever sees origin `:7787`. Prod: same-path reverse proxy assumption holds.

### 6.2 WebSocket client (`api/ws.ts`)

- Single socket, connected at app boot.
- Reconnect with exponential backoff (0.5s → 8s cap, jitter). On reconnect:
  `agentStore.hydrate()` + refetch active conversation (WS events missed while down are
  recovered via REST, not replayed).
- Heartbeat: respond to server pings; if no message for 30s, force-reconnect.
- Connection state exposed as a tiny store → header status dot + ErrorBanner.

### 6.3 Event dispatcher

One switch, strongly typed on the envelope from SPEC §5.2:

| Event `type` | Action |
|---|---|
| `token` | `chatStore._appendToken` |
| `task.done` | `chatStore._commitStream()`; trigger TTS if voice enabled (§7.2) |
| `agent.status` | `agentStore._applyStatus` |
| `delegation` | `agentStore._applyDelegation` (spawns DelegationFlight) |
| `tool.call` / `tool.result` | appended to the owning AgentCard's task line |
| `error` | `chatStore._fail` + ErrorBanner |

Unknown event types are logged and ignored (forward compatibility with backend additions).

### 6.4 Types (`api/types.ts`)

DTOs are hand-mirrored from the backend in one file with a version comment pinned to the
SPEC section. Contract tests (§9.2) validate fixtures against these types. (OpenAPI codegen is
a candidate once the backend stabilizes — noted as future work, not v1.)

---

## 7. Voice (v1 — browser)

### 7.1 STT

- Feature-detect `SpeechRecognition` / `webkitSpeechRecognition`. If absent (e.g. Firefox):
  MicButton hidden, tooltip in settings explains browser support. **Chat must be fully usable
  with voice unavailable.**
- **Push-to-talk** (default): hold MicButton (mouse/space) → interim transcript streams into
  Composer → release → final transcript sent as chat message.
- **Hands-free** (toggle): continuous recognition; a final result is auto-sent. Recognition
  pauses while TTS is speaking (prevents SOUL hearing itself).

### 7.2 TTS

- Speak only the Super Agent's **final** synthesized reply (`task.done`), never intermediate
  tokens or sub-agent chatter.
- Markdown stripped before speaking; code blocks replaced with "…code block omitted…".
- New user input immediately cancels in-flight speech.

### 7.3 Orb state machine

```
idle ──mic down──▶ listening ──send──▶ thinking ──task.done──▶ speaking ──tts end──▶ idle
  ▲                                        │ (voice off: skip speaking)
  └────────────────────────────────────────┘
```

Priority when signals conflict: `listening > speaking > thinking > idle`.

---

## 8. Design System — Yellow & Black

### 8.1 Tokens (`theme/tokens.css`, mirrored in Tailwind config)

| Token | Value | Use |
|---|---|---|
| `--bg` | `#0A0A0A` | App background |
| `--surface` | `#141414` | Panels, cards |
| `--surface-2` | `#1E1E1E` | Hover/raised, code blocks |
| `--border` | `#2A2A26` | Hairlines |
| `--accent` | `#FFC800` | SOUL identity: orb, active states, primary buttons, focus rings |
| `--accent-dim` | `#B38C00` | Secondary accent, borders of active cards |
| `--accent-glow` | `rgba(255,200,0,.15)` | Glows/shadows only |
| `--text` | `#F5F5F0` | Primary text |
| `--text-muted` | `#8A8A80` | Secondary text, timestamps |
| `--ok` / `--warn` / `--err` | `#4ADE80` / `#FBBF24` / `#F87171` | Status dots only |

Rules: yellow is **never** a large fill or body-text color; user bubbles are `--surface-2`,
SOUL bubbles are `--surface` with a 2px `--accent-dim` left border; exactly one glowing
element per region (the active one).

### 8.2 Typography & motion

- UI text: Inter (self-hosted, `font-display: swap`). Telemetry/agent metadata + code:
  JetBrains Mono.
- Motion: 150–250ms ease-out for UI; orb and delegation flights may run longer. All
  non-essential motion disabled under `prefers-reduced-motion` (and the settings override).

### 8.3 Accessibility

- Contrast: all text ≥ 4.5:1 on its surface (`#FFC800` on `#0A0A0A` ≈ 12:1 — used for
  accents/headings only, verified per-pairing).
- Full keyboard path: send, mic (Space held on focused MicButton), drawer, rail navigation.
- Streamed replies: `aria-live="polite"` on the *committed* message (not per-token, which
  would spam screen readers).
- Focus rings: 2px `--accent`, never removed.

---

## 9. Testing Strategy

| Layer | Tool | What |
|---|---|---|
| Unit | Vitest | Stores (token append/commit, delegation ring buffer), `ws.ts` backoff/dispatch (mock socket), `stt/tts` wrappers (mock Web Speech), markdown-strip-for-TTS |
| Component | RTL + Vitest | Composer send semantics, StreamingMessage render throttle, AgentCard status transitions, ErrorBanner on WS drop |
| Contract | Vitest fixtures | Every WS event type + REST response fixture must parse against `api/types.ts` — fixtures updated only from real backend payloads |
| E2E smoke | Playwright | Boot app against a **mock WS/REST server** (`tests/e2e/mock-server.ts`): send message → tokens stream → delegation appears → task completes. Runs headless in CI |

Voice E2E is manual (Web Speech is not automatable headlessly) — a `docs/`-tracked manual
test checklist covers it. Target: stores and `api/` at ~90% line coverage; components smoke-level.

---

## 10. Build, Run, Quality Gates

```bash
npm run dev        # Vite on :7787, proxy → :7788
npm run build      # tsc --noEmit && vite build → dist/
npm run preview    # serve dist/ on :7787
npm test           # vitest run
npm run e2e        # playwright (starts mock server)
npm run lint       # eslint + prettier check
```

- Bundle budget: initial JS ≤ 250KB gzip (shiki + framer-motion lazy/split).
- CI (GitHub Actions, later PR): lint → typecheck → unit/component → e2e smoke → build.
- `.env`: `VITE_API_TARGET` (default `http://localhost:7788`) — the only env knob.

---

## 11. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Web Speech STT unsupported (Firefox) / flaky | Feature-detect; chat-first design; v2 local Whisper path already in SPEC §4 |
| Backend not ready while UI is built first | Mock WS/REST server from §9.4 doubles as the dev backend (`npm run dev:mock`) — UI is fully demoable standalone |
| Token-stream rendering jank on long replies | Streaming buffer separated from message list; throttled markdown parse; virtualization only if profiling demands it (not v1) |
| WS event contract drift vs Spring backend | Single `types.ts` + contract fixtures; drift fails tests, not users |

---

## 12. Implementation Order (UI-first plan)

1. **Scaffold** — Vite + TS + Tailwind + tokens; header/layout shells; port 7787.
2. **Mock server** — `dev:mock` serving SPEC §5 REST + WS with canned streams.
3. **Chat core** — chatStore + ws client + MessageList/Composer/StreamingMessage.
4. **Agent rail** — agentStore + AgentCard + delegation events + flights.
5. **SoulOrb** — state machine + animations.
6. **Voice** — voiceStore + STT/TTS wrappers + MicButton + orb wiring.
7. **Settings drawer + polish** — model rebind, a11y pass, bundle budget check.

Each step lands with its tests; the mock server means every step is visually verifiable
without the Spring backend existing.
