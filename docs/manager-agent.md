# SOUL — Manager Agent (Skills & Hooks)

Design document for the Super Agent ("SOUL") — the manager the user talks to — and the
**skillpool / hookspool** system: repo-root pools of capabilities and lifecycle behaviors that
**any agent** can use, selected per agent in config.

| | |
|---|---|
| **Version** | 0.1 (Draft) |
| **Date** | 2026-07-13 |
| **Status** | Accepted — §9 decisions resolved 2026-07-13 |
| **Parent docs** | [SPEC.md](SPEC.md) §3 · [ollama-model-management.md](ollama-model-management.md) |
| **Home** | `soul-orchestrator/` (agent runtime) · `skillpool/` · `hookspool/` |

---

## 1. Scope

Two repo-root pools provide reusable, **agent-agnostic** building blocks:

- **`skillpool/`** — *capabilities an agent chooses to use.* Each skill is described to the
  model; the model decides when to invoke one (like a tool). Model-driven.
- **`hookspool/`** — *behaviors that fire automatically* at fixed lifecycle points
  (message received, before/after a skill runs, before responding, …). Deterministic,
  not model-driven — hooks can observe, modify, or block.

A skill or hook belongs to **no agent in particular**. Any agent may use any of them; each
agent's config simply **names** the skills and hooks it's given (§5). The orchestrator loads
each pool once and hands every agent a *filtered view* built from its list. This mirrors
Claude Code's model: skills are opt-in capabilities an agent reaches for; hooks are guardrails
and automation the system enforces.

**This phase builds one agent — the Manager** — but the pools, registries, and per-agent
binding are the multi-agent-ready design from the start, so sub-agents later reuse the exact
same skills and hooks by just listing them.

**In scope:** the shared skill/hook pools and their manifests, per-agent selection, discovery,
the execution/IO protocol, the Manager agent loop, and security.
**Out of scope (later phases):** sub-agents and delegation themselves (SPEC §3.2–3.3), the
tool layer beyond skills, remote skills.

---

## 2. The Manager Agent

The Manager is the first agent built on the shared pools; every later agent is the same
machinery with a different model, a different skill list, and a different hook list.

### 2.1 Role

SOUL is the agent the user addresses (chat or voice). It:

1. **Understands** the user's intent.
2. **Answers directly** for conversational turns, or
3. **Invokes one or more of its skills** when a task needs a real capability, in an agentic loop.
4. **Synthesizes** a single, voice-friendly reply.

The Manager sees only the skills and hooks its config names (§5) — not the whole pool. Hooks
fire throughout (§4) so the system can enforce policy and add automation around every step
without the model's cooperation.

### 2.2 Model

Only the Manager's model is provisioned now ([models.yaml](../soul-scripts/ollama/models.yaml)):

```yaml
models:
  - name: llama3.1:8b
    roles: [super]
    required: true
    warm: true
```

**Why `llama3.1:8b`:** the Manager's core job is reliable **tool/function calling** plus solid
instruction-following and light planning. `llama3.1` has native tool-calling support in Ollama,
runs in ~6 GB (fits this machine's 15 GB RAM, CPU-only), and is warmed so the first message
isn't a cold load. The binding is config, not code — `qwen2.5:7b-instruct` is a drop-in
alternative if tool-calling quality needs tuning. Sub-agent models return to the manifest as
those agents are built.

### 2.3 The agentic loop

```
user message
   │
   ▼
[hooks: user_message_received] ──► can rewrite / reject
   │
   ▼
[hooks: before_model] ──► inject context (memory, active-skill prompts)
   │
   ▼
┌─► call model  (Ollama /api/chat, tools = discovered skills) ◄─┐
│      │                                                        │
│      ├─ final answer ─► [hooks: before_respond] ─► stream to UI
│      │                                                        │
│      └─ skill call(s) requested                               │
│              │                                                │
│      [hooks: before_skill] ─► can block/modify                │
│              ▼                                                │
│         run skill (§3.4) ─► result                            │
│              │                                                │
│      [hooks: after_skill]                                     │
│              └───────── append result to conversation ────────┘
└──────────────────────────────  (loop, bounded by max_steps)
```

- Only **this agent's** skills (from its config list, §5) are exposed as **tools** (§3.3); the
  model picks among them by description. The loop itself is identical for any agent.
- The loop is bounded (`max_steps`, default 6) so a misbehaving model can't spin forever.
- Everything streams to the UI over the existing WebSocket contract (SPEC §5.2): tokens,
  `agent.status`, and — reused for skills — `tool.call` / `tool.result` events.

---

## 3. skillpool/

### 3.1 What a skill is

A self-contained capability in its own directory. Two flavors:

- **script skill** — runs an executable and returns a result the model can use (e.g. read a
  file, fetch a URL, do a calculation).
- **prompt skill** — injects extra instructions/context into the model when relevant (e.g. a
  house style guide, a domain cheat-sheet). No code runs; it shapes the model's behavior.

### 3.2 Layout & manifest

```
skillpool/
└── <skill-name>/
    ├── skill.yaml       # metadata (below)
    ├── run.py           # entrypoint  (script skills)
    └── prompt.md        # injected text (prompt skills)
```

```yaml
# skillpool/web-fetch/skill.yaml
name: web-fetch
description: >
  Fetch a URL and return its readable text. Use when the user asks about the
  contents of a specific web page or link.
version: 0.1
type: script                 # script | prompt
entrypoint: run.py           # script skills
timeout_seconds: 20
parameters:                  # JSON-Schema — becomes the model-facing tool schema
  type: object
  properties:
    url: { type: string, description: "The URL to fetch" }
  required: [url]
permissions:                 # least-privilege declaration (enforced by the runtime)
  network: true
  filesystem: none           # none | workspace | readonly-workspace
```

`description` + `parameters` are what the model sees — they must read like a good tool
description, because that's exactly how the model decides to use the skill.

### 3.3 Discovery → per-agent tool schema

At startup (and on a `skillpool/` change), the orchestrator scans every `skill.yaml`,
validates it, and builds one **global registry** — the pool is loaded once, independent of any
agent. When an agent starts, it's given a **filtered view**: only the skills its config lists
(§5). Each **script** skill in that view becomes an Ollama tool offered to that agent's model:

```json
{ "type": "function",
  "function": { "name": "web-fetch",
                "description": "Fetch a URL and return its readable text. …",
                "parameters": { "type": "object", "properties": { "url": {…} }, "required": ["url"] } } }
```

All of the agent's script skills are exposed at once (no pre-filtering/router yet — deferred
until a catalog exceeds ~15–20; see §9). Prompt skills aren't tools; a `before_model` step
decides (by description match or an explicit `always: true`) whether to inject their `prompt.md`.

### 3.4 Invocation protocol

When the model calls a script skill, the runtime executes its entrypoint with a **JSON-in /
JSON-out** contract (same shape as the hook protocol, §4.3):

- **stdin**: `{ "skill": "web-fetch", "input": { "url": "…" }, "context": { "conversationId": "…" } }`
- **stdout**: `{ "ok": true, "output": "…text…", "display": "fetched 4.2 KB from example.com" }`
- **exit 0** = success; **non-zero** = failure, stderr becomes the error the model sees.

`output` is fed back to the model as the tool result; `display` (optional) is what the UI's
activity view shows. The contract is **language-neutral from day one**: the runner dispatches
`entrypoint` by its shebang, so a skill may be Python, Node, bash, or any executable — the
shipped examples just happen to be Python.

### 3.5 Example skills shipped in phase 1

| Skill | Type | Purpose |
|---|---|---|
| `echo` | script | Trivial reference skill — returns its input; proves the loop end-to-end |
| `current-time` | script | Returns the local date/time (models don't know "now") |
| `persona` | prompt | Injects SOUL's JARVIS-style tone/voice guidance |

---

## 4. hookspool/

### 4.1 What a hook is

An event-triggered behavior the **system** runs — the model neither chooses nor sees it. Hooks
are how SOUL enforces safety, logs, and automates. A hook can:

- **observe** — logging, metrics, telemetry (exit 0, ignored output);
- **modify** — rewrite the message or inject context (return a patch);
- **block** — veto a skill/tool call or a response (non-zero exit + reason).

### 4.2 Layout, events & manifest

```
hookspool/
└── <hook-name>/
    ├── hook.yaml
    └── run.py
```

```yaml
# hookspool/block-secrets/hook.yaml
name: block-secrets
description: Refuse skill calls whose arguments contain obvious credentials.
event: before_skill          # see the event table below
matcher: ".*"                # optional: skill-name / content regex to narrow when it runs
entrypoint: run.py
blocking: true               # may veto; a non-blocking hook can only observe
always-apply: true           # optional: run for EVERY agent, un-skippable (safety gates only)
timeout_seconds: 5
```

| Event | Fires | A blocking hook can… |
|---|---|---|
| `session_start` | new conversation | — (setup only) |
| `user_message_received` | each user turn, pre-model | reject or rewrite the message |
| `before_model` | before each model call | inject system context |
| `before_skill` | before a script skill runs | block or rewrite the skill's input |
| `after_skill` | after a skill returns | redact or annotate the result |
| `before_respond` | before the final reply streams | block or edit the reply |
| `session_end` | conversation closed | — (teardown/flush) |
| `on_error` | any step errors | observe (alerting) |

### 4.3 Execution protocol

Same JSON-in/JSON-out discipline as skills, so one small runner serves both:

- **stdin**: `{ "event": "before_skill", "payload": { "skill": "web-fetch", "input": {…} }, "context": {…} }`
- **stdout** (optional): `{ "action": "allow" | "block" | "modify", "reason": "…", "patch": {…} }`
- **exit code**: `0` = allow (default). A **blocking** hook exiting non-zero = **block**; stderr
  is the reason surfaced to the model/user. Non-blocking hooks' exit codes are logged, not enforced.

Ordering: hooks for the same event run in directory-name order; the first `block` wins and
short-circuits. Every hook invocation is logged and, where user-visible, streamed to the UI.

### 4.4 Example hooks shipped in phase 1

| Hook | Event | Purpose |
|---|---|---|
| `audit-log` | `before_skill`, `after_skill` | Append every skill call + result to an audit log (observe) |
| `block-secrets` | `before_skill` | Block skill args containing obvious secrets (blocking) |
| `inject-time` | `before_model` | Add the current timestamp to context every turn (modify) |

---

## 5. Configuration — pools are global, selection is per-agent

`soul-orchestrator/src/main/resources/application.yml` (extends SPEC §9). The **pool paths are
global**; each **agent names** the skills and hooks it uses. That per-agent list is the whole
"mention the agent to use it" mechanism — the same skill/hook is reused by any agent that lists
it, and skills/hooks themselves stay agent-neutral.

```yaml
soul:
  pools:                          # loaded once, shared by every agent
    skills: { path: ../skillpool, enabled: true }
    hooks:  { path: ../hookspool, enabled: true, default-timeout-seconds: 5 }

  agents:
    super:                        # the Manager (this phase)
      model: llama3.1:8b
      persona: jarvis
      max-steps: 6                # agentic-loop bound
      skills: [echo, current-time, persona]         # names from skillpool/ ("*" = all)
      hooks:  [audit-log, block-secrets, inject-time]

    # Later agents reuse the SAME pools by just listing what they need:
    # coder:
    #   model: qwen2.5-coder:7b
    #   skills: [web-fetch, run-tests]
    #   hooks:  [audit-log]
```

Rules:

- A name in an agent's `skills`/`hooks` must exist in the pool, or startup fails (typo guard).
- `"*"` grants the whole pool to that agent (handy in dev; explicit lists in production).
- An agent with no `skills`/`hooks` key gets none — capabilities are opt-in, never implicit.
- **Exception:** a hook with `always-apply: true` runs for every agent regardless of its list
  and can't be opted out (safety gates only). These are the one thing not per-agent.
- Pool paths are configurable so tests can point at fixtures. `manage.py verify` already checks
  each agent's `model` stays within [models.yaml](../soul-scripts/ollama/models.yaml); it will
  also verify every listed skill/hook resolves in the pool.

---

## 6. Security

Skills and hooks run **arbitrary local code**, so they are the main trust boundary (SPEC §10):

- **Least privilege.** Each skill declares `permissions`; the runtime enforces them (no network
  unless `network: true`, filesystem scoped to a workspace dir). Undeclared access is denied.
- **Sandboxed execution.** Entrypoints run as subprocesses with a timeout, a scrubbed
  environment, no inherited secrets, resource limits, and a restricted working directory. A
  container-per-skill option is held in reserve for skills that later need stronger isolation (§9.4).
- **Hooks are the safety gates.** A `before_skill` blocking hook is how SOUL refuses dangerous
  actions — this is a feature, deterministic and model-independent.
- **Provenance.** Only pools inside the repo are loaded; adding a skill/hook is a reviewed file
  change (PR), never a runtime download.
- **Full audit trail.** Every skill and hook invocation is logged and surfaced in the UI
  activity view, so nothing runs invisibly.

---

## 7. Directory layout

```
soul/
├── skillpool/
│   ├── README.md                  # the skill contract
│   ├── echo/            { skill.yaml, run.py }
│   ├── current-time/    { skill.yaml, run.py }
│   └── persona/         { skill.yaml, prompt.md }
├── hookspool/
│   ├── README.md                  # the hook contract
│   ├── audit-log/       { hook.yaml, run.py }
│   ├── block-secrets/   { hook.yaml, run.py }
│   └── inject-time/     { hook.yaml, run.py }
└── soul-orchestrator/             # loads both pools once; runs each agent's loop
    └── src/main/java/com/soul/orchestrator/
        ├── agent/       # Agent + agentic loop; ManagerAgent is the first instance
        ├── skills/      # SkillRegistry (global) → per-agent view; SkillRunner (JSON protocol)
        ├── hooks/       # HookRegistry (global) → per-agent view; HookRunner, event dispatch
        └── ollama/      # streaming chat + tool calls
```

Pools live at the repo root — not inside the orchestrator and not under any agent — so they're
language-agnostic, obviously the shared extension surface, and reused across agents. The
orchestrator loads each pool into a global registry once; every agent (Manager now, sub-agents
later) receives a filtered view from its config list (§5).

---

## 8. Implementation Plan

| Phase | Deliverable |
|---|---|
| **1** | `skillpool/` + `hookspool/` with the manifests, the READMEs (the contracts), and the example skills/hooks above — runnable standalone via the JSON protocol |
| **2** | Orchestrator: global `SkillRegistry` + `HookRegistry` (discovery/validation), per-agent filtered views from config, and the shared subprocess `Runner` |
| **3** | `ManagerAgent`: Ollama streaming chat with tool-calls, the bounded agentic loop, the agent's selected skills exposed as tools |
| **4** | Hook dispatch wired into every lifecycle point; blocking/veto semantics; audit logging |
| **5** | Permission enforcement + sandboxing; UI activity-view events for skills/hooks |

Phase 1 is independently testable (drive each skill/hook through its stdin/stdout contract with
no model or orchestrator), matching how the rest of SOUL has been built mock-first.

---

## 9. Decisions (resolved 2026-07-13)

1. **Skill selection at scale → expose all now, router later.** Every skill in an agent's view
   is offered to the model as a tool. A retrieval/router step is deferred until a catalog grows
   past ~15–20 skills; the Manager starts with a handful, so it isn't needed yet. (§3.3)
2. **Prompt-skill activation → description match + `always: true`.** A prompt-skill injects when
   its description matches the turn, or when it declares `always: true` (e.g. `persona`). (§3.1, §3.3)
3. **Skill/hook language → language-neutral from day one.** The stdin/stdout JSON contract is
   language-agnostic; entrypoints are dispatched by shebang, so a skill or hook may be Python,
   Node, bash, anything executable. Shipped examples stay Python. (§3.4, §4.3)
4. **Sandbox → subprocess + limits now, container later.** Isolation is subprocess-based:
   timeout, scrubbed environment, restricted working directory, resource limits. A
   container-per-skill option is revisited only if a skill needs stronger isolation. (§6)
5. **Hook policy → per-agent opt-in, with mandatory safety gates.** Hooks are selected per agent
   (§5), except a hook may declare pool-level `always-apply: true` to run for **every** agent,
   un-skippable — reserved for safety gates like `block-secrets`. Everything else stays opt-in. (§4.2, §5)
