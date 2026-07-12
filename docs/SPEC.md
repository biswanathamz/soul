# SOUL — Specification Document

**S**upervised **O**rchestration of **U**nified **L**LM-agents

| | |
|---|---|
| **Version** | 0.1 (Draft) |
| **Date** | 2026-07-12 |
| **Status** | Proposed |
| **Repository** | `soul` |

---

## 1. Overview

SOUL is a locally hosted, multi-agent AI assistant inspired by Iron Man's JARVIS. A user
interacts with a single **Super Agent** (the "Manager") through a modern web interface using
**chat and voice**. The Super Agent never does specialist work itself — it understands intent,
plans, and delegates tasks to a fleet of **Sub-Agents**, each backed by the local Ollama model
best suited to that kind of work. Results flow back up to the Super Agent, which synthesizes a
single coherent answer for the user.

Everything runs locally: no cloud LLM APIs, no data leaving the machine.

### 1.1 Goals

- One conversational entry point (chat + voice) to a team of specialist agents.
- Manager/worker agent hierarchy: the Super Agent plans, routes, supervises, and aggregates.
- Per-task model selection — each sub-agent is bound to the local Ollama model that fits its job.
- Clean microservice split: a React UI service and a Spring Boot backend service, with Python
  used for agent-side scripts/tooling.
- Modern, striking **yellow & black** UI served on port **7787**.

### 1.2 Non-Goals (v1)

- Multi-user support / authentication (single local user).
- Cloud model providers (OpenAI, Anthropic, etc.).
- Mobile apps (responsive web only).
- Long-term autonomous agents running unattended (v1 agents are task-scoped and terminate).

---

## 2. System Architecture

```
                        ┌────────────────────────────────────────────┐
                        │              Browser (User)                │
                        │        Chat  +  Voice (mic / speaker)      │
                        └──────────────────┬─────────────────────────┘
                                           │ http://localhost:7787
                        ┌──────────────────▼─────────────────────────┐
                        │   soul-console  (React UI microservice)    │
                        │   - Chat window, voice controls            │
                        │   - Agent activity / delegation view       │
                        └──────────────────┬─────────────────────────┘
                                           │ REST + WebSocket (:7788)
                        ┌──────────────────▼─────────────────────────┐
                        │  soul-orchestrator (Spring Boot backend)   │
                        │                                            │
                        │   ┌──────────────────────────────────┐     │
                        │   │  SUPER AGENT ("Manager")         │     │
                        │   │  intent → plan → delegate →      │     │
                        │   │  supervise → synthesize          │     │
                        │   └───┬────────┬────────┬────────┬───┘     │
                        │       │        │        │        │         │
                        │   ┌───▼──┐ ┌───▼──┐ ┌───▼──┐ ┌───▼──┐      │
                        │   │Coder │ │Rsrch │ │Write │ │ Sys  │ ...  │
                        │   │Agent │ │Agent │ │Agent │ │Agent │      │
                        │   └───┬──┘ └───┬──┘ └───┬──┘ └───┬──┘      │
                        └───────┼────────┼────────┼────────┼─────────┘
                                │        │        │        │
                        ┌───────▼────────▼────────▼────────▼─────────┐
                        │        Ollama (local, :11434)              │
                        │   different model per agent role           │
                        └────────────────────────────────────────────┘
                                           │
                        ┌──────────────────▼─────────────────────────┐
                        │   soul-scripts (Python tool scripts)       │
                        │   invoked by agents for real-world work:   │
                        │   file ops, shell, web scrape, STT/TTS...  │
                        └────────────────────────────────────────────┘
```

### 2.1 Microservices

| Service | Name | Tech | Port | Responsibility |
|---|---|---|---|---|
| UI | **`soul-console`** | React 18 + Vite + TypeScript | **7787** | Chat/voice interface, agent activity dashboard, settings |
| Backend | **`soul-orchestrator`** | Java 21 + Spring Boot 3.x | **7788** | Agent hierarchy, Ollama integration, task routing, conversation state, WebSocket streaming |
| Scripts | **`soul-scripts`** | Python 3.11+ | n/a (invoked as processes) | Tool execution layer for agents: file/system operations, web fetch, speech (Whisper/Piper) fallback, utilities |
| Model runtime | Ollama | — | 11434 | Serves all local LLMs |

`soul-console` and `soul-orchestrator` are the two independently deployable microservices.
`soul-scripts` is a script package invoked by the orchestrator (via `ProcessBuilder`), not a
long-running service in v1 — it can be promoted to a FastAPI sidecar later if needed.

---

## 3. Agent System

### 3.1 Super Agent (Manager) — "SOUL"

The single agent the user talks to. Persona: calm, competent, lightly witty (JARVIS-like).

Responsibilities:

1. **Understand** — parse user intent from chat/voice input.
2. **Answer or delegate** — trivial conversational turns are answered directly; real tasks are
   decomposed into a plan.
3. **Route** — assign each plan step to the best-fit sub-agent.
4. **Supervise** — track sub-agent progress, retry/reassign on failure, enforce timeouts.
5. **Synthesize** — merge sub-agent outputs into one voice-friendly response.
6. **Narrate** — stream status to the UI ("Delegating to the Coder agent…") so the user sees
   the delegation happen live.

### 3.2 Sub-Agents (v1 roster)

Each sub-agent = role definition + system prompt + bound Ollama model + allowed tools.

| Agent | Role | Suggested Ollama model* | Tools (via soul-scripts) |
|---|---|---|---|
| `coder` | Write/review/explain code | `qwen2.5-coder` | file read/write, shell (sandboxed) |
| `researcher` | Web lookup, summarize, fact-find | `llama3.1` | web fetch, search |
| `writer` | Long-form writing, docs, emails | `gemma2` | file write |
| `analyst` | Reasoning, math, planning-heavy work | `deepseek-r1` | none (pure reasoning) |
| `sysops` | Local machine tasks (files, processes) | `llama3.1` | file ops, shell (sandboxed) |

\* Models are **configuration, not code** — bound in `application.yml` and changeable without
rebuild. The Super Agent itself runs on a strong general model (default `llama3.1`, or the
largest model the machine can serve). Final model choices depend on available RAM/VRAM.

### 3.3 Delegation Protocol (internal)

- Super Agent produces a structured plan: `[{step, agentRole, instruction, dependsOn[]}]`.
- Independent steps run **in parallel** (bounded worker pool; default 2 concurrent Ollama
  generations to respect local hardware).
- Every sub-agent returns a structured result: `{status, output, artifacts[], error?}`.
- Failures: 1 automatic retry with error context → then Super Agent either reassigns or
  reports the failure honestly to the user.
- Every delegation, tool call, and result is emitted as an **event** over WebSocket so the UI
  can render a live "mission control" view.

---

## 4. Voice Pipeline

**v1 (browser-native, zero extra install):**

- **STT**: Web Speech API (`SpeechRecognition`) in the browser → transcript sent as a normal
  chat message.
- **TTS**: Web Speech API (`speechSynthesis`) speaks the Super Agent's final reply.
- Push-to-talk button + optional hands-free toggle; UI shows a live waveform/level indicator.

**v2 (fully local, higher quality — via soul-scripts):**

- STT: `faster-whisper` Python script; audio uploaded to orchestrator.
- TTS: Piper (local neural TTS) streamed back to the browser.

The API is designed so the UI doesn't care which pipeline is active (`voice.mode` config flag).

---

## 5. API Design (soul-orchestrator, port 7788)

### 5.1 REST

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/chat` | Send a user message `{conversationId?, text}` → `{conversationId, messageId}` (reply streams over WS) |
| `GET` | `/api/v1/conversations/{id}` | Fetch conversation history |
| `GET` | `/api/v1/agents` | List agents, their roles, bound models, live status |
| `GET` | `/api/v1/models` | Proxy of Ollama's installed models |
| `PUT` | `/api/v1/agents/{role}/model` | Rebind an agent to another model |
| `POST` | `/api/v1/voice/transcribe` | (v2) audio blob → transcript |
| `GET` | `/actuator/health` | Health check |

### 5.2 WebSocket — `/ws/stream`

Server → client event envelope:

```json
{ "type": "token | agent.status | delegation | tool.call | tool.result | task.done | error",
  "conversationId": "…",
  "agent": "super | coder | researcher | …",
  "payload": { } }
```

- `token` — streamed response tokens from the Super Agent (typed out live in the UI).
- `agent.status` — `idle | thinking | delegating | working | done | failed`.
- `delegation` — Super Agent handed a step to a sub-agent (drives the activity view).

### 5.3 Ollama integration

- Spring `WebClient` against `http://localhost:11434/api/chat` (streaming NDJSON).
- One model binding per agent role; keep-alive tuned so frequently used models stay warm.

---

## 6. UI/UX — soul-console (port 7787)

### 6.1 Design language

- **Theme: black & yellow.** Near-black base `#0A0A0A` / surface `#141414`, primary accent
  `#FFC800` (yellow), text `#F5F5F0`, muted `#8A8A80`. Yellow is used deliberately — accents,
  active states, the Super Agent's identity glow — never as large fills.
- Modern "mission control" aesthetic: dark glassy panels, subtle glow on the active agent,
  smooth micro-animations, monospace accents for agent/system telemetry.
- A central **SOUL orb/avatar** that animates with state: idle pulse → listening (mic level) →
  thinking (rotating) → speaking (waveform).

### 6.2 Screens / regions

1. **Chat panel** (primary) — streamed markdown-rendered replies, code blocks with copy button,
   input bar with mic push-to-talk.
2. **Agent activity rail** — live cards for each sub-agent: status, current task, bound model,
   elapsed time. Delegations animate from the SOUL orb to the agent card.
3. **Settings drawer** — model bindings per agent, voice on/off & voice selection, Ollama
   endpoint, theme intensity.

### 6.3 Stack

React 18 + TypeScript + Vite, Tailwind CSS (custom yellow/black tokens), Zustand for state,
native WebSocket client, `react-markdown` + `shiki` for rendering. Dev server and production
serve both on **7787**.

---

## 7. Repository Layout

```
soul/
├── docs/
│   └── SPEC.md                  # this document
├── soul-console/                # React UI microservice (port 7787)
│   ├── src/
│   │   ├── components/          # ChatPanel, AgentRail, SoulOrb, VoiceControl…
│   │   ├── state/               # Zustand stores
│   │   ├── api/                 # REST + WS clients
│   │   └── theme/               # yellow/black design tokens
│   └── package.json
├── soul-orchestrator/           # Spring Boot backend microservice (port 7788)
│   ├── src/main/java/com/soul/orchestrator/
│   │   ├── agent/               # SuperAgent, SubAgent, AgentRegistry, Planner
│   │   ├── ollama/              # OllamaClient (streaming), ModelBinding
│   │   ├── conversation/        # ConversationService, persistence
│   │   ├── tools/               # ToolExecutor → invokes soul-scripts
│   │   ├── ws/                  # WebSocket event streaming
│   │   └── api/                 # REST controllers
│   └── src/main/resources/application.yml   # agent↔model bindings, ports
├── soul-scripts/                # Python tool layer
│   ├── tools/                   # file_ops.py, web_fetch.py, shell_exec.py…
│   ├── voice/                   # transcribe.py (whisper), speak.py (piper) [v2]
│   └── pyproject.toml
└── docker-compose.yml           # optional: run everything with one command
```

---

## 8. Data & State

- **v1 persistence**: H2 (file mode) via Spring Data JPA — zero-setup, single user.
  Entities: `Conversation`, `Message`, `Task`, `Delegation`, `AgentConfig`.
- Conversation context sent to models is windowed (last N turns + rolling summary) to fit
  local-model context limits.
- Upgrade path to PostgreSQL if/when needed (JPA makes this a config change).

---

## 9. Configuration (single source of truth: `application.yml`)

```yaml
soul:
  ollama:
    base-url: http://localhost:11434
    max-concurrent-generations: 2
  super-agent:
    model: llama3.1
    persona: jarvis          # prompt preset
  agents:
    coder:      { model: qwen2.5-coder, tools: [file, shell] }
    researcher: { model: llama3.1,      tools: [web] }
    writer:     { model: gemma2,        tools: [file] }
    analyst:    { model: deepseek-r1,   tools: [] }
    sysops:     { model: llama3.1,      tools: [file, shell] }
  voice:
    mode: browser            # browser | local (v2)
  scripts:
    python-bin: python3
    path: ../soul-scripts
```

---

## 10. Security & Safety

- All services bind to `localhost` only.
- Tool execution (shell/file) is **allowlisted and sandboxed**: sub-agents may only touch a
  configured workspace directory; destructive shell patterns are blocked; every tool call is
  logged and visible in the UI activity rail.
- CORS restricted to `http://localhost:7787`.
- No secrets in v1 (no external APIs).

---

## 11. Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Ollama | latest | **Not yet installed on the dev machine** — required before backend work starts. Pull at least `llama3.1` and `qwen2.5-coder`. |
| Java (JDK) | 21+ | for soul-orchestrator |
| Node.js | 20+ | for soul-console |
| Python | 3.11+ | for soul-scripts |
| RAM | 16 GB+ recommended | multiple warm local models |

---

## 12. Milestones

| Phase | Deliverable | Scope |
|---|---|---|
| **P0 — Skeleton** | Both services boot; health checks; UI shell with theme | Project scaffolding, ports 7787/7788 wired, CORS, WS handshake |
| **P1 — Talk to SOUL** | Chat with the Super Agent (single model, no sub-agents) | Ollama streaming, conversation persistence, streamed chat UI |
| **P2 — Delegation** | Manager plans & routes to sub-agents; live activity rail | Planner, agent registry, parallel execution, WS event stream |
| **P3 — Tools** | Sub-agents act on the world via soul-scripts | Tool executor, sandboxing, file/web/shell tools |
| **P4 — Voice** | Hands-free JARVIS experience | Browser STT/TTS, push-to-talk, orb animations |
| **P5 — Polish** | v2 voice (Whisper/Piper), settings UI, docker-compose | Quality + packaging |

---

## 13. Open Questions

1. **Hardware**: how much RAM/VRAM is available? This decides model sizes (7B vs 14B+) and
   whether sub-agents can truly run in parallel.
2. **Model roster**: confirm which Ollama models to standardize on once Ollama is installed.
3. **Wake word** ("Hey Soul") — wanted in v1 voice, or is push-to-talk enough?
4. **Sub-agent roster**: is the v1 set (coder/researcher/writer/analyst/sysops) right, or are
   there domain-specific agents you already have in mind?
