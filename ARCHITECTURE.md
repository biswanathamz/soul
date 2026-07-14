# SOUL — Architecture

> **SOUL** — *Supervised Orchestration of Unified LLM-agents* — is a locally hosted,
> JARVIS-inspired multi-agent assistant. You talk to one **Manager** agent (chat or
> voice); it reasons, calls tools, and answers — all backed by local **Ollama** models,
> with nothing leaving your machine.

This document is the map of the whole system: what each piece is, how the pieces talk,
and the principles that hold it together. For the elevator pitch see [README.md](README.md);
for deep design notes see `docs/` (local-only, gitignored).

---

## 1. Design principles

These are the invariants every component is built to respect.

1. **Local-first, private by default.** Every model runs on your hardware through Ollama.
   No cloud LLM APIs, no telemetry, no data leaving the box.
2. **One agent to talk to, many to do the work.** The user has a single conversational
   surface — the **Manager** (a.k.a. Super Agent). Specialists are an implementation
   detail the Manager orchestrates, not something the user juggles.
3. **Separate services, clear contracts.** UI, orchestration, and tooling are independent
   microservices that only ever meet at versioned wire contracts (REST + WebSocket).
   Any one can be swapped or run standalone.
4. **Capabilities are agent-agnostic and shared.** Skills and hooks live in root-level
   pools (`skillpool/`, `hookspool/`). *Any* agent can use *any* capability — an agent's
   config just names which ones it's granted. Capabilities are never coupled to one agent.
5. **Skills are chosen; hooks are enforced.** A **skill** is a tool the model may *decide*
   to call. A **hook** is lifecycle behaviour the *system* runs whether the model likes it
   or not — including un-skippable safety gates (`always-apply`).
6. **Language-neutral tooling.** Skills and hooks speak a JSON-over-stdin/stdout protocol
   and are dispatched by shebang, so a capability can be a Python script, a shell script,
   a prompt file — anything that reads stdin and writes stdout.
7. **Contract-first.** The wire contract (REST + WebSocket, §5) is the source of truth
   between the console and the orchestrator, so either side can evolve independently as
   long as the contract holds.

---

## 2. System at a glance

```
                    ┌───────────────────────────────────────────────┐
                    │                    Browser                     │
                    │   soul-console  (React + Vite, :7787)          │
                    │   chat · voice · agent rail · yellow/black UI  │
                    └───────────────┬──────────────────┬────────────┘
                        REST (commands/queries)   WebSocket (stream)
                     POST /api/v1/chat, GET …    /ws/stream : tokens,
                                │                  tool.call, status…
                    ┌───────────▼──────────────────▼────────────────┐
                    │        soul-orchestrator  (Spring Boot, :7788) │
                    │                                                │
                    │   ChatController ─► ManagerAgent (agent loop)  │
                    │        │                 │        │            │
                    │   CapabilityResolver   HookDispatcher  Runner  │
                    │        │                 │        │            │
                    │   ┌────▼─────┐      ┌─────▼────┐  ┌─▼────────┐  │
                    │   │ Skill    │      │ Hook     │  │ Ollama   │  │
                    │   │ Registry │      │ Registry │  │ HttpClient│ │
                    │   └────┬─────┘      └────┬─────┘  └────┬─────┘  │
                    └────────┼─────────────────┼────────────┼────────┘
                     reads   │          reads  │   HTTP via host.containers.internal
                    ┌────────▼───────┐ ┌────────▼───────┐ ┌─▼──────────────────────┐
                    │  skillpool/    │ │  hookspool/    │ │  Ollama  :11434         │
                    │ echo, current- │ │ audit-log,     │ │  HOST-NATIVE (GPU)      │
                    │ time, persona  │ │ block-secrets… │ │  llama3.1:8b … (0.0.0.0)│
                    └────────────────┘ └────────────────┘ └────────────────────────┘
           ── containerized ──                            ──── on the host ────

     soul-console ──POST /voice/api/v1/tts──▶ soul-voice (:7789, Piper neural TTS,
                    ◀──── audio/wav ────────  containerized, CPU-only)

     soul-scripts/ollama —— declarative model management (manifest + manage.py),
                            run on the host against Ollama's REST API, out of band
```

The console, orchestrator, and voice service run as **containers**; **Ollama runs
host-native on the GPU**. Four long-running services (**console**, **orchestrator**,
**voice**, **ollama**) plus one out-of-band toolchain (**scripts**) and two shared
capability pools (**skillpool**, **hookspool**).

---

## 3. Components

### 3.1 `soul-console` — the UI  ·  `:7787`  ·  React 18 + TypeScript + Vite

The single human surface: a modern black-and-yellow console for chatting with (and
speaking to) the Manager, and watching it work in real time.

| Area | Files | Responsibility |
| --- | --- | --- |
| **API layer** | `src/api/{http,rest,ws,socket,types}.ts` | REST client for commands/queries; reconnecting WebSocket client for the event stream; shared DTO types. |
| **State** | `src/state/*Store.ts` (Zustand) | `chatStore`, `agentStore`, `connectionStore`, `voiceStore`, `settingsStore`, `uiStore`; `dispatcher.ts` routes inbound WS events into stores. |
| **Chat** | `src/components/chat/*` | Composer, message list, streaming message, markdown + code-block rendering. |
| **Agents** | `src/components/agents/*` | Agent rail + cards showing each agent's status/capabilities. |
| **Voice** | `src/voice/{stt,tts}.ts`, `components/voice/MicButton` | Speech-to-text in, text-to-speech out. |
| **Presence** | `src/components/orb/SoulOrb.tsx` | The animated "SOUL" orb reflecting agent state. |
| **Theme** | `src/theme/tokens.css`, `src/index.css` | Yellow/black design tokens. |

The console is a pure client: it holds **no** business logic about agents — it renders
whatever the orchestrator tells it over the two channels.

### 3.2 `soul-orchestrator` — the brain  ·  `:7788`  ·  Spring Boot 3.3 / Java 17

Owns the Manager agent, the agentic loop, capability resolution, hook enforcement, and
all talking to Ollama. Java packages under `com.soul.orchestrator`:

| Package | Key types | Responsibility |
| --- | --- | --- |
| `web` | `ChatController`, `AgentsController`, `ModelsController`, `Dtos`, `WebConfig` | REST surface + CORS. Turns HTTP requests into agent runs and reads. |
| `ws` | `StreamWebSocketHandler`, `EventSink`, `WsEvent`, `WebSocketConfig` | The `/ws/stream` broadcast channel; `EventSink` is how the agent emits live events. |
| `agent` | `ManagerAgent`, `CapabilityResolver`, `AgentCapabilities` | The agent loop; resolves which skills/hooks an agent is granted (merging `always-apply` hooks). |
| `skills` | `SkillRegistry`, `SkillManifest` | Loads + validates `skillpool/`; exposes skills as Ollama tool specs. |
| `hooks` | `HookRegistry`, `HookManifest`, `HookDispatcher`, `HookOutcome` | Loads `hookspool/`; fires hooks at lifecycle points; enforces `observe`/`modify`/`block` outcomes. |
| `ollama` | `OllamaHttpClient`, `StubOllamaClient`, `OllamaClient`, `ChatMessage`, `ToolCall`, `ToolSpec`, `ChatTurn` | The Ollama `/api/chat` client (NDJSON streaming, tool-call parsing). Stub swaps in under the `stub-ollama` profile for tests. |
| `runtime` | `Runner`, `RunnerResult` | Executes a skill/hook entrypoint as a subprocess over the JSON stdin/stdout protocol. |
| `conversation` | `ConversationStore`, `StoredMessage` | In-memory conversation history (per `conversationId`). |
| `config` | `SoulProperties` | Binds `soul.*` config (ollama, web, pools, agents). |

**Profiles.** Default profile → real `OllamaHttpClient` against `localhost:11434`.
`stub-ollama` profile → `StubOllamaClient` for deterministic tests (32 JUnit tests, incl.
`ManagerAgentTest` driving the full loop against the *real* pools with only the LLM stubbed).

### 3.3 Ollama — the model runtime  ·  `:11434`  ·  host-native (GPU)

The local inference server. Holds the pulled models and serves `/api/chat` (tool-calling,
streaming). The current manifest ships one model — `llama3.1:8b`, role `super` (the
Manager's brain).

**Runs on the host, not in a container** — deliberately, so it uses the NVIDIA GPU directly
(rootless podman can't reach the GPU here without the NVIDIA Container Toolkit + a CDI spec).
It binds `0.0.0.0:11434` so the orchestrator *container* can reach it via
`host.containers.internal`. The orchestrator tolerates Ollama being unreachable at boot,
surfacing an `error` event rather than crashing.

### 3.3b `soul-voice` — neural TTS  ·  `:7789`  ·  Python (FastAPI + Piper)

SOUL's voice (docs/voice-and-face.md). A stateless local TTS service: the console posts
text, gets back `audio/wav` in a natural female voice (Piper, default `en_US-amy-medium`).
Runs as a container, **CPU-only by design** — it never competes with Ollama for the GPU.

- `POST /api/v1/tts` `{text, voice?, speed?}` → WAV · `GET /api/v1/voices` · `GET /health`
- Reached same-origin from the browser via the `/voice/*` proxy (Vite in dev, nginx in prod).
- The console speaks answers **sentence-by-sentence as tokens stream** (splitter + ordered
  audio queue in `soul-console/src/voice/`), falling back to browser `speechSynthesis`
  if the service is down. Voice *manner* (polite, warm) lives in the `persona` skill.

### 3.4 `soul-scripts/ollama` — model management  ·  Python

Declarative, out-of-band lifecycle for models — **not** on the request path.

- `models.yaml` — the manifest: which models must exist, their role, `required`/`warm` flags.
- `manage.py` — `status` / `sync` / `pull` / `rm` / `prune` / `warm` / `verify`.
- `pooltest.py` (repo root under `soul-scripts/`) — validates and smoke-tests every skill
  and hook in the pools (CI parity, no model needed).

Driven through `make models-*`. Keeps the set of installed models in sync with intent.

### 3.5 The capability pools — `skillpool/` & `hookspool/`

Root-level, **agent-agnostic**. Loaded once by the orchestrator and shared by every agent;
an agent's config lists which entries it's granted.

**Skills** — model-chosen tools. Each is a directory with a manifest + entrypoint:

| Skill | Type | What |
| --- | --- | --- |
| `echo` | script | Returns its input (canonical example / test fixture). |
| `current-time` | script | Returns local date/time; accepts an optional `strftime` `format`. |
| `persona` | prompt | The Manager's persona/system framing (`skill.yaml` marks it always-on). |

**Hooks** — system-enforced lifecycle behaviour, one of three modes:

| Hook | Mode | Event(s) | What |
| --- | --- | --- | --- |
| `audit-log` | observe | before/after skill | Logs skill invocations; never alters flow. |
| `block-secrets` | **block** | `before_skill` | **`always-apply`** safety gate — vetoes any skill call whose args contain obvious credentials. Un-skippable, runs for every agent. |
| `inject-time` | modify | `before_model` | Appends the current time into the system prompt so the model isn't blind to "now". |

See §5 for the protocol both pools speak.

---

## 4. How a request flows — "What is the current time?"

The canonical end-to-end path, tracing one user message:

1. **UI → REST.** Console sends `POST /api/v1/chat { conversationId?, message }`.
   Orchestrator persists the user turn, returns `{ conversationId, messageId }`
   immediately, and kicks off the Manager **asynchronously** (executor-backed).
2. **UI opens/reuses the stream.** Console is subscribed to `WS /ws/stream`; all live
   progress for the run arrives here — the REST call only acknowledged receipt.
3. **Resolve capabilities.** `CapabilityResolver` computes the Manager's granted skills
   (`echo`, `current-time`, `persona`) and hooks, **merging in every `always-apply` hook**
   (so `block-secrets` is present even if unlisted).
4. **`before_model` hooks.** `HookDispatcher` runs modify-hooks — `inject-time` appends the
   real current time into the system prompt.
5. **Model call.** `OllamaHttpClient` calls Ollama `/api/chat` with the message history and
   the granted skills as tool specs, streaming the response (NDJSON). Tokens are emitted to
   the UI as `token` events as they arrive.
6. **Tool decision.** The model returns a tool call: `current-time { format: "%H:%M …" }`.
   Emit `tool.call`.
7. **`before_skill` hooks + safety gate.** `block-secrets` inspects the args. If they held a
   credential it would **veto** here — emit an `error` ("blocked"), skip the skill. For a
   time query it passes.
8. **Run the skill.** `Runner` executes `skillpool/current-time/run.py` as a subprocess over
   JSON stdin/stdout; the result feeds back as a `tool` message. Emit `tool.result`
   (and `after_skill` observe-hooks like `audit-log` fire).
9. **Model finalizes.** The loop returns to Ollama with the tool result; the model streams
   the final answer ("The current time is …"). Bounded by `max-steps` (6) so it can't loop
   forever.
10. **Done.** Emit `task.done` with the final text, persist the assistant turn to
    `ConversationStore`, and emit `agent.status` back to idle. The UI has rendered the whole
    thing live; a later `GET /api/v1/conversations/{id}` returns the persisted transcript.

---

## 5. Contracts

### 5.1 Transport split — REST + WebSocket

Deliberately **hybrid**, each channel for what it's good at:

- **REST** — commands and queries: fire an action, read state.
  `POST /api/v1/chat` · `GET /api/v1/conversations/{id}` · `GET /api/v1/agents` ·
  `PUT /api/v1/agents/{id}/model` · `GET /api/v1/models`.
  Chat returns `{conversationId, messageId}` and returns *immediately*; the answer streams.
- **WebSocket** `/ws/stream` — the live, server-push event stream for a run. Event types:
  `token`, `agent.status`, `tool.call`, `tool.result`, `task.done`, `error`.

Rationale: REST gives simple, cacheable, testable request/response semantics for
actions and reads; WS gives low-latency server push for tokens and progress that REST
polling can't match. Same-origin in the browser via the Vite/nginx proxy.

### 5.2 Skill/Hook protocol — JSON over stdin/stdout

A capability is a directory with a YAML manifest (`skill.yaml` / `hook.yaml`) and an
entrypoint. The orchestrator's `Runner` invokes the entrypoint as a subprocess (dispatched
by shebang — language-neutral), writes the invocation as **JSON to stdin**, and reads the
result as **JSON from stdout**.

- **Skill manifest** declares `name`, `description` (fed to the model), `parameters`
  (JSON-Schema the model fills), `timeout_seconds`, `permissions`.
- **Hook manifest** declares `event` (`before_model` / `before_skill` / `after_skill`,
  string or list), `matcher`, `blocking`, and `always-apply`. A blocking hook's stdout can
  **allow** or **block**; a modify hook returns a patch (e.g. `append_system`); an observe
  hook's result is ignored for control flow.

This is what makes capabilities portable and agent-agnostic: neither pool imports anything
from the orchestrator, and nothing in a manifest names a specific agent.

### 5.3 Configuration — `soul.*` (`application.yml`)

```yaml
soul:
  ollama:  { base-url: ${OLLAMA_HOST:http://localhost:11434}, request-timeout-seconds: 120 }
  web:     { cors-allowed-origin: http://localhost:7787 }
  pools:                          # loaded once, shared by every agent
    skills: { path: ../skillpool, enabled: true }
    hooks:  { path: ../hookspool, enabled: true, default-timeout-seconds: 5 }
  agents:
    super:                        # the Manager
      model: llama3.1:8b
      persona: jarvis
      max-steps: 6
      skills: [echo, current-time, persona]
      hooks:  [audit-log, block-secrets, inject-time]
```

Adding an agent = a new entry under `agents:` naming the pooled capabilities it may use.
No code change to grant an existing skill/hook to a new agent.

---

## 6. Runtime topology & deployment

**Ollama always runs host-native (on the GPU); the orchestrator and console are containers.**
The orchestrator container reaches the host's Ollama via `host.containers.internal:11434`
(hence Ollama binds `0.0.0.0`). The two app services can *also* be run host-side for dev.

| Piece | Normal | Dev (host-side) |
| --- | --- | --- |
| **Ollama** | `make ollama-install` — host-native systemd service, GPU, `0.0.0.0:11434` | same (or `make ollama-serve` foreground) |
| **orchestrator** | `make up` — container, built image | `./gradlew bootRun` (→ `localhost:11434`) |
| **console** | `make up` — container (nginx) | `make dev` (Vite) |

Container engine is **podman-compose** by default (`COMPOSE=` overrides to `docker compose`).
Models are pulled host-side with `make models-sync` (`manage.py` over Ollama's REST API — no
container). Because everything meets over `localhost`/`host.containers.internal`, host and
container processes interoperate freely — and only the app containers ever show in `podman ps`
(Ollama is a host process).

**CI** (`.github/workflows/`): `verify-models.yml` (manifest), `verify-pools.yml` (skill/hook
smoke tests), `verify-orchestrator.yml` (JUnit), `verify-voice.yml` (soul-voice contract
tests, Piper stubbed). `make verify` runs the whole set locally.

---

## 7. Repository layout

```
soul/
├── ARCHITECTURE.md            ← this file
├── README.md                  the pitch + quick start
├── Makefile                   the entire lifecycle (make help)
├── docker-compose.yml         the app containers: orchestrator + voice + console (Ollama is host-native)
│
├── soul-console/              React + Vite UI (:7787)
│   ├── src/{api,state,components,voice,theme,lib}/
│   └── docker/                nginx/Dockerfile for the UI
│
├── soul-orchestrator/         Spring Boot Manager (:7788)
│   ├── src/main/java/com/soul/orchestrator/{web,ws,agent,skills,hooks,ollama,runtime,conversation,config}/
│   ├── src/main/resources/application.yml
│   └── Dockerfile             build context = repo root (bakes in the pools)
│
├── soul-voice/                neural TTS — FastAPI + Piper (:7789)
│   ├── app.py                 /api/v1/tts, /api/v1/voices, /health
│   └── Dockerfile             bakes in the voice models (Amy, Alba)
│
├── soul-scripts/
│   ├── ollama/{models.yaml,manage.py}   declarative model management
│   └── pooltest.py                      validate + smoke-test the pools
│
├── skillpool/                 model-chosen tools (echo, current-time, persona)
├── hookspool/                 system-enforced hooks (audit-log, block-secrets, inject-time)
├── .github/workflows/         CI: models, pools, orchestrator, voice
└── docs/                      design docs (SPEC, TDDs, voice-and-face)
```

---

## 8. Roadmap

SOUL today is a **single Manager agent** doing real tool-use over local models. The
architecture is built for where it's going:

- **Sub-agent fleet.** Coder / researcher / writer / analyst / sysops specialists, each on
  the Ollama model best suited to it. The `agents:` config, agent-agnostic pools, and the
  `CapabilityResolver` already generalize past one agent — a sub-agent is a new config entry
  plus a delegation skill on the Manager.
- **Delegation & synthesis.** The Manager decomposes intent into tasks, dispatches to
  sub-agents, and synthesizes results into one answer.
- **Richer UI telemetry.** A live activity rail surfacing skill/hook events per agent.
- **Persistence.** Durable conversation history beyond the in-memory store.
- **Model routing.** Automatic selection of the right local model per task.

---

*Grounded in the code as of this commit. When a component moves, update this file in the
same change — it's the map people read first.*
