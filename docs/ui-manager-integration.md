# SOUL — Wiring the UI to the Manager Agent

Design for making the web UI talk to the **real** Manager agent (llama3.1:8b via Ollama)
instead of the mock — chat and voice reaching the actual `soul-orchestrator`.

| | |
|---|---|
| **Version** | 0.1 (Draft) |
| **Date** | 2026-07-13 |
| **Status** | Proposed |
| **Parent docs** | [SPEC.md](SPEC.md) §5 · [TDD-soul-console.md](TDD-soul-console.md) · [manager-agent.md](manager-agent.md) |
| **Touches** | `soul-orchestrator/` (new web surface + Manager loop) · `soul-console/` (activity view) · `docker-compose.yml` |

---

## 1. Goal & current state

The user should chat/speak to the Manager and get real answers from the local model, with the
UI showing what the Manager is doing (thinking, running a skill, a hook blocking something).

Today:

- **soul-console** already speaks the full contract (SPEC §5): REST `/api/v1/chat`,
  `/api/v1/agents`, `/api/v1/models`, `/api/v1/conversations/{id}`; WebSocket `/ws/stream`
  with events `token`, `agent.status`, `delegation`, `tool.call`, `tool.result`, `task.done`,
  `error`. It talks to the **mock** orchestrator.
- **soul-orchestrator** (real) has phases 1–2: registries, per-agent capabilities, subprocess
  runner. It has **no web surface** and doesn't call Ollama yet.

**Design principle:** the mock already defines the contract the UI depends on. The real
orchestrator implements that **same contract**, so the UI needs *no change to keep working* —
the only required work is backend + compose. UI changes (below) are enhancements to surface
skills and hooks, which the mock never had.

---

## 2. What changes where

| Component | Change | Required? |
|---|---|---|
| `soul-orchestrator` | Add the web surface (REST + WebSocket) implementing SPEC §5 | **Yes** |
| `soul-orchestrator` | ManagerAgent: Ollama streaming chat + agentic loop + hook dispatch, emitting WS events (manager-agent.md phases 3–4) | **Yes** |
| `soul-orchestrator` | Ollama client + `/api/v1/models` proxy; in-memory conversation store | **Yes** |
| `docker-compose.yml` | Point `soul-console` at the real orchestrator; add the Ollama dependency | **Yes** |
| `soul-console` | Repurpose the agent rail into a **Manager activity** view (skills in flight, hook actions) | Recommended |
| `soul-console` | Show the Manager's skills/hooks (from an extended `/agents`) | Recommended |

The split matters: with the required backend work alone, the UI immediately talks to the real
Manager. The UI enhancements make skill/hook activity visible instead of an empty rail.

---

## 3. Backend: soul-orchestrator gains a web surface

Implements the exact contract the UI already calls (SPEC §5), so soul-console is a drop-in client.

### 3.1 REST

| Method | Path | Behavior |
|---|---|---|
| `POST` | `/api/v1/chat` | `{conversationId?, text}` → `{conversationId, messageId}`; the Manager runs and streams over WS |
| `GET` | `/api/v1/agents` | `[{ role:"super", model, status, description, skills:[…], hooks:[…] }]` — one agent for now |
| `GET` | `/api/v1/models` | Proxy of Ollama `/api/tags` (installed models) |
| `PUT` | `/api/v1/agents/{role}/model` | Rebind the Manager's model (updates the running config) |
| `GET` | `/api/v1/conversations/{id}` | History for reload/rehydrate |
| `GET` | `/actuator/health` | Health (Spring Actuator) |

Extending `/api/v1/agents` with `skills`/`hooks` arrays is backward-compatible — the UI ignores
unknown fields today and can render them once ready. `skills`/`hooks` come straight from the
agent's `AgentCapabilities` (phase 2).

### 3.2 WebSocket `/ws/stream`

Server→client envelope is unchanged (SPEC §5.2). The Manager's lifecycle maps onto the existing
event types — **no new event type is required** for a working v1:

| Manager moment | WS event | payload |
|---|---|---|
| Manager starts reasoning | `agent.status` (agent `super`) | `{status:"thinking"}` |
| Runs a skill | `tool.call` (agent `super`) | `{tool:"current-time", args}` |
| Skill returns | `tool.result` | `{tool, summary}` |
| Streams the reply | `token` | `{messageId, token}` |
| Final answer | `task.done` | `{messageId, text}` → UI commits + speaks |
| Failure / blocked by a hook | `error` | `{message}` |
| Back to idle | `agent.status` | `{status:"idle"}` |

A skill is exactly a "tool" from the UI's perspective, so `tool.call`/`tool.result` fit with no
change. Hook **blocks** surface as `error` for v1 (see §6 open question for a richer `hook` event).

### 3.3 ManagerAgent runtime (manager-agent.md phases 3–4)

- **Ollama client**: `WebClient` streaming `POST /api/chat` (NDJSON), the Manager's script skills
  passed as `tools`. Model + `max-steps` from config.
- **Agentic loop**: `user_message_received` → `before_model` hooks → model call → if tool-calls,
  `before_skill` hooks (may block) → `Runner` executes the skill → `after_skill` hooks → loop
  (bounded) → `before_respond` → stream tokens → `task.done`. Every step emits the WS events above.
- **Hooks** fire via the phase-2 `Runner`; blocking hooks (e.g. `block-secrets`, always-apply)
  can veto a skill call, surfaced to the user.
- **Conversation store**: in-memory for now (mirrors the mock; SPEC §8 H2 later), windowed
  context to fit the local model.

### 3.4 Config

```yaml
soul:
  ollama:
    base-url: ${OLLAMA_HOST:http://localhost:11434}   # container sets host.containers.internal:11434
  web:
    cors-allowed-origin: http://localhost:7787
```

Ollama is host-native (on the GPU); the orchestrator container is pointed at it with
`OLLAMA_HOST=http://host.containers.internal:11434`, and `localhost:11434` is the default for
host dev (`./gradlew bootRun`).

---

## 4. Frontend: soul-console

With the backend live, chat/voice already reach the Manager. Two enhancements make it good:

1. **Activity rail → Manager activity.** Today the rail lists sub-agents and filters out `super`
   (`roles.filter(r => r !== 'super')`), so with only the Manager it's empty. Repurpose it into a
   view of the Manager's **skills** (idle/running, driven by `tool.call`/`tool.result`) and recent
   **hook actions** (e.g. "block-secrets ✋ blocked a call"). When sub-agents arrive later, the rail
   shows both tiers.
2. **Capabilities display.** Read `skills`/`hooks` from `/api/v1/agents` and show what the Manager
   can do (a small "capabilities" list in the rail or settings).

Unchanged and already correct: the SoulOrb (Manager state), streamed markdown replies, voice
(STT/TTS), the connection/error banners, and the settings model-rebind (now hitting real Ollama
models via `/api/v1/models`).

Minimal path: ship the backend first with **zero UI changes** — the Manager works, the rail is
just empty — then land the activity view.

---

## 5. Compose rewiring

```
[container] soul-console ──/api,/ws──► [container] soul-orchestrator ──host.containers.internal──► [host] Ollama (GPU)
```

- `soul-console.ORCHESTRATOR_URL` → the real `soul-orchestrator` service (both containers).
- The orchestrator container reaches host Ollama via `host.containers.internal:11434`
  (`extra_hosts: ["host.containers.internal:host-gateway"]`); no `depends_on` on Ollama since
  it's a host process and the Manager tolerates it being unreachable at boot.
- Ollama runs host-native on the GPU; the model is provisioned host-side with `make models-sync`.
  There is no mock backend.

---

## 6. Open questions

1. **Hook visibility.** v1 maps a hook block to `error`. Worth adding a dedicated `hook` WS event
   (`{hook, action:"block"|"modify", reason}`) so the UI can show hook activity distinctly (e.g. a
   shield icon) rather than as a generic error? Leaning yes, as a backward-compatible addition.
2. **Manager in the rail.** Show the Manager as its own card (model, status, live skill) in addition
   to the orb, or keep the orb as its sole representation and use the rail only for skills/hooks?
3. **Streaming granularity.** Stream `token` events during the model's final answer only, or also
   narrate intermediate steps ("running current-time…") as assistant-visible text vs. rail-only?
4. **Real vs mock default.** *Resolved:* the mock backend was removed — `make up` runs the real
   stack (orchestrator + console containers) against host-native Ollama. No mock path remains.

---

## 7. Plan

| Step | Deliverable |
|---|---|
| **1** | Ollama `WebClient` + `ManagerAgent` loop (manager-agent.md phase 3): chat, skills as tools, bounded loop |
| **2** | Web surface: REST controllers + `/ws/stream`, mapping the loop to WS events (§3.1–3.2) |
| **3** | Hook dispatch wired into the loop (phase 4); blocks surfaced to the UI |
| **4** | Compose rewiring: orchestrator + console containers → host-native Ollama (`make up`) |
| **5** | UI: activity rail (skills/hooks) + capabilities display |

Steps 1–2 already let the UI talk to the real Manager end-to-end; 3–5 add hooks and visibility.
