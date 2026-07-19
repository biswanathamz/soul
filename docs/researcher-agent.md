# SOUL — Researcher Sub-Agent & the Command/Event Protocol

> Branch: `feature/researcher-agent` · Status: **draft for review**
> Depends on: SPEC.md §5, manager-agent.md (the agent loop), ARCHITECTURE.md
>
> SOUL grows its first **worker agent**. The Manager doesn't know anything recent —
> so when a query needs live knowledge, it delegates to the **Researcher**, which
> gathers evidence from the web across multiple sources. The Manager then **always
> summarizes** the findings into one coherent, cited answer. The two agents talk
> through an explicit **Command/Event protocol** — commands say *do this*, events
> say *this happened* — and the UI shows exactly which agent is working.

---

## 1. Goals

1. **A Researcher worker agent** — the first real sub-agent, proving the fleet design.
2. **Recency via delegation** — the Manager recognizes "I don't know current facts,"
   delegates to the Researcher, and never invents recent data.
3. **Manager always synthesizes — calibrated by confidence.** The user hears one
   answer, from the Manager, summarizing the Researcher's findings with sources.
   Results carry a **confidence score**; on low confidence the Manager retries with
   different sources or tells the user honestly — it never presents shaky findings
   as fact. Sub-agents never talk to the user directly.
4. **Visible orchestration** — the UI clearly indicates which sub-agent is working,
   on what, and with which tool (the JARVIS "workshop" feeling).
5. **Multi-source collection** — the Researcher searches and reads several
   independent sources, not one.

### System-level goals

6. **Per-agent models** — each agent binds its own Ollama model via config
   (`agents.<name>.model`); the Researcher may run a different model than the Manager.
7. **Command/Event communication** — Manager ↔ sub-agent messaging uses two distinct,
   first-class data models:
   - **Command** — an *imperative*: "do something." Directed at one agent. Pending work.
   - **Event** — a *fact*: "something happened." Past tense, immutable, broadcast.
   Commands and events are separate classes with separate buses — never mixed.
8. **One `delegate` tool, forever — routed by capability.** Delegation is generic
   (`delegate{capability, task}`) through an **Agent Registry** indexed by namespaced
   **capabilities** (`research.search`, `task.schedule`, `email.send`). The Manager
   asks *"who supports `research.search`?"* — it never hardcodes "call researcher."
   Agents are swappable providers behind capabilities; the eventual fleet (task,
   calendar, email, WhatsApp, browser, vision, finance, memory) joins by declaring
   capabilities — never by adding `delegate_<x>` tools, which doesn't scale.

## 2. Non-goals (v1)

- More workers (coder/writer/analyst/sysops) — this builds the *pattern*; they follow later.
- Parallel fan-out to multiple sub-agents in one turn (protocol allows it; v1 runs one).
- Distributed transport (buses are in-process; the protocol is transport-agnostic so a
  queue could replace it later without touching agents).
- Long-term research memory / caching of findings.
- Deep crawling, JS-rendered pages, paywalls, PDFs.

---

## 3. The Command/Event protocol

The heart of this feature. Plain immutable Java records in a new package
`com.soul.orchestrator.protocol`:

### 3.1 `AgentCommand` — "do this"

```java
public record AgentCommand(
    UUID id,                      // correlation key — events reference it
    String type,                  // "task" | "cancel"  (the two v1 imperatives)
    String issuedBy,              // "super"
    String target,                // "researcher" — exactly one recipient
    String conversationId,
    Map<String, Object> payload,  // task:   {task: "latest Node.js LTS version", …}
                                  // cancel: {commandId: <the task command to stop>}
    Instant issuedAt) { }
```

### 3.2 `AgentEvent` — "this happened"

```java
public record AgentEvent(
    UUID id,
    UUID commandId,               // which command this is about
    String conversationId,        // which conversation — what the WS bridge routes on (§6)
    String type,                  // GENERIC lifecycle: "task.started" | "task.progress"
                                  //   | "task.completed" | "task.failed" | "task.cancelled"
    String agent,                 // who did it — "researcher"
    Map<String, Object> payload,  // domain data lives here — researcher's completed:
                                  //   {findings, sources:[{title,url}]}
    Instant occurredAt) { }
```

> **Why events carry `conversationId`.** The WS bridge must route each event to a
> conversation, and an event that had to *look one up* by command id would race the
> Manager clearing that delegation the moment its terminal event lands. Every event
> belongs to a command, which belongs to a conversation — so the event carries it and
> stays self-contained. Factories therefore take the `AgentCommand` itself
> (`AgentEvent.started(command, agent)`), which also makes an event that isn't about a
> real command unconstructable.

**The lifecycle vocabulary is worker-agnostic on purpose.** Every worker — researcher
today; calendar, email, browser, vision, finance, memory tomorrow — emits the same
five `task.*` event types. Domain specifics travel in `payload`. That means the
Manager's pending-delegation await and the WS bridge are written **once** and never
touched again when new workers join. Naming rule: commands name work to do; event
types are past-tense facts.

### 3.2c `task.progress` — staged, so the UI feels alive

Progress isn't a bare ping; its payload narrates the work in stages:

```json
{"stage": "searching",   "label": "Searching the web…"}
{"stage": "found",       "label": "Found 3 sources",              "total": 3}
{"stage": "reading",     "label": "Reading nodejs.org (1/3)",     "step": 1, "total": 3}
{"stage": "reading",     "label": "Reading endoflife.date (2/3)", "step": 2, "total": 3}
{"stage": "summarizing", "label": "Summarizing findings…"}
```

`stage` is a machine key (worker-specific vocabulary); `label` is the ready-to-render
human line. The WS bridge maps each progress event to `agent.status{status: working,
task: label}` — so the delegation strip and face caption tick through
*Searching… → Found 3 sources → Reading 2/3 → Summarizing* with **zero new console
event handling**. One event per stage transition, no token streaming from workers.

### 3.2b `TaskResult` — every completion carries confidence

A `task.completed` payload isn't a free-form map: it has a standard shape, so the
Manager can reason about ANY worker's result the same way:

```java
public record TaskResult(
    double confidence,            // 0.0–1.0 — how sure the worker is of its result
    String summary,               // the worker's own condensation of what it did/found
    Map<String, Object> data) { } // domain payload — researcher: {sources:[{title,url}]}
```

```json
{ "confidence": 0.94,
  "summary": "Node.js 22 'Jod' is the current LTS (three sources agree).",
  "data": { "sources": [{"title": "…", "url": "…"}, …] } }
```

Confidence is generic on purpose — a future `email.send` worker reports how sure it
is the mail actually went out, `task.schedule` that the slot was really free. What
the Manager does with the number is policy (§5.1).

### 3.3 The buses

```java
public interface CommandBus { void send(AgentCommand command); void register(String target, Consumer<AgentCommand> worker); }
public interface EventBus   { void publish(AgentEvent event);  void subscribe(Consumer<AgentEvent> listener); }
```

- `InProcessCommandBus`: routes each command to its target's registered worker,
  executed on that worker's own executor (one thread pool per sub-agent — a slow
  researcher never blocks the Manager's loop).
- `InProcessEventBus`: synchronous broadcast to subscribers.
- Two standing subscribers: the **Manager** (to complete pending delegations) and the
  **WS bridge** (to turn protocol events into UI events, §6).
- The Manager awaits a terminal event (`task.completed` / `task.failed` /
  `task.cancelled`) for its command id via a `CompletableFuture` with a timeout
  (`soul.delegation.timeout-seconds`, default 120). Timeout ⇒ the Manager issues a
  **cancel command** (§3.5) — a timed-out worker must actually stop, not keep
  burning CPU behind an abandoned future.

### 3.4 The Agent Registry — capability-indexed

Agents advertise **capabilities**: namespaced `domain.action` verbs. The registry
answers one question — *"who supports this capability?"* — so callers never bind to
agent names, and agents are swappable providers:

```java
public record AgentDescriptor(
    String name,                  // "researcher"
    String description,          // "Finds current, real-world information on the web."
    Set<String> capabilities) { } // {"research.search", "research.fetch"}

public interface AgentRegistry {
    void register(AgentDescriptor descriptor, Consumer<AgentCommand> worker);
    Optional<AgentDescriptor> whoSupports(String capability); // THE routing question
    Set<String> capabilities();          // union — feeds the delegate tool's enum
    List<AgentDescriptor> available();
}
```

- **Config IS the registry's content** — each agent's entry declares what it offers,
  and workers register at startup with the descriptor built from it (registration
  also wires the worker's command consumer to the `CommandBus`):

  ```yaml
  agents:
    researcher:
      capabilities: [research.search, research.fetch]
    # future workers join the same way — nothing else changes:
    # task:          {capabilities: [task.schedule, reminder.create]}
    # communication: {capabilities: [email.send, whatsapp.send]}
  ```

- **Capabilities vs skills** — distinct on purpose. *Capabilities* are the agent's
  public contract (what others may ask of it). *Skills* (the pool) are its private
  toolbox for fulfilling them: the researcher advertises `research.search` /
  `research.fetch` and fulfills them internally with `web-search` / `fetch-page`.
- Duplicate providers: v1 — first registration wins, log a warning; later — a
  priority field enables failover/choice between providers of the same capability.
- The registry is the source of truth for the `delegate` tool (§5): the `capability`
  enum and tool description are generated from it. **Adding a worker = declaring its
  capabilities. No new tools, no Manager code, no prompt edits.**

### 3.5 Cancellation — "Stop."

Research takes tens of seconds on this hardware; the user must be able to abort:

```
Researching… (40 s in)          User: "Stop"  (button, or voice)
   │                                   │
   │                     POST /api/v1/conversations/{id}/cancel
   │                                   │
   │            Manager: AgentCommand(type: cancel,
   │                     payload: {commandId: <active task>}) → CommandBus
   ▼                                   │
ResearcherWorker: cancellation flag set for that command id
   → the AgentLoop checks it COOPERATIVELY at every step boundary
     (before each model call, before each skill run) and aborts the
     in-flight Ollama request where possible
   → emits task.cancelled → WS: researcher status idle
Manager's future completes as cancelled → the delegate tool returns
"cancelled by user" → Manager acknowledges briefly ("Alright, stopped.")
```

- **One mechanism, three triggers**: the user's stop button, a voice "stop"
  (wake-word path), and the Manager's own **timeout** all send the same
  `cancel` command. No special cases.
- Cancellation is *cooperative* — a step in progress may take a moment to wind
  down; the UI shows "stopping…" until `task.cancelled` arrives.
- Cancelling an already-finished command is a harmless no-op (events won).

## 4. The Researcher agent

### 4.1 Runtime — the loop, generalized

`ManagerAgent` currently hardcodes agent `"super"`. Extract the reusable core into
**`AgentLoop`** (hooks → model → skills-as-tools → hooks, bounded by `max-steps`) so
both agents are thin wrappers:

- **ManagerAgent** = AgentLoop(super) + user I/O (REST/WS, conversation store) +
  the generic **delegate** tool (§5).
- **ResearcherWorker** = AgentLoop(researcher) + registry registration: consumes a
  `task` command, runs its loop, publishes events. No conversation store, no
  direct user output — its "answer" is the payload of `task.completed`.

Capabilities keep working unchanged: `CapabilityResolver` already filters the shared
pools per agent and force-merges `always-apply` hooks (`block-secrets` gates the
Researcher too — automatically).

### 4.2 Model — per agent (system goal 6)

```yaml
soul:
  agents:
    super:
      model: llama3.1:8b               # the Manager delegates via capabilities —
                                       # no hardcoded worker list here
    researcher:
      model: llama3.1:8b               # its own binding — MAY differ per agent
      persona: researcher
      max-steps: 8
      capabilities: [research.search, research.fetch]   # public contract (registry)
      skills: [web-search, fetch-page, current-time]    # private toolbox
      hooks:  [audit-log]              # block-secrets joins via always-apply

  delegation:
    timeout-seconds: 120           # generic — applies to any worker
    confidence: {retry-below: 0.4, hedge-below: 0.7, max-retries: 1}   # policy — §5.1
  research:
    max-sources: 4                 # researcher-specific knob
```

> **Hardware honesty (4 GB GPU):** two *different* models can't co-reside in VRAM;
> Ollama swaps them per delegation (tens of seconds each way). Default therefore
> binds the Researcher to the **same** model as the Manager (zero swap cost); a
> smaller dedicated model (e.g. `llama3.2:3b`) is fully supported via config and
> `models.yaml` (new role `researcher`) for bigger GPUs.

### 4.3 Research skills — multi-source collection (goal 5)

New pool entries (agent-agnostic, like everything in `skillpool/`):

| Skill | What | Notes |
| --- | --- | --- |
| `web-search` | Query the web, return top results `[{title, url, snippet}]` | Provider-agnostic via the **SearchConnector layer** (below). Manifest declares `permissions.network: true`. |
| `fetch-page` | Fetch a URL → readable plain text (title + main content, truncated ~6 kB) | Strips nav/boilerplate heuristically; timeouts + size caps; http(s) only, no redirects off-scheme. |
| `researcher-persona` | Prompt skill: gather → verify across sources → condense to findings **with source URLs**; never editorialize | Granted only to the researcher. |

**The Connector layer — skills never call a provider directly.** Inside `web-search`,
a `SearchConnector` interface fronts pluggable providers, exactly the pattern any
future integration (Gmail, calendar, WhatsApp) will follow:

```
web-search skill ──► SearchConnector.search(query, max) → [{title, url, snippet}]
                        ├── duckduckgo   (default — no key, no setup)
                        ├── searxng      (self-hosted metasearch — SEARXNG_URL)
                        ├── brave        (BRAVE_API_KEY)
                        ├── tavily       (TAVILY_API_KEY)
                        └── google       (GOOGLE_CSE_KEY + GOOGLE_CSE_ID)
```

- **Selection + fallback chain by env**, injected by the orchestrator's `Runner`
  when it spawns the skill: `SOUL_SEARCH_PROVIDER=duckduckgo` and
  `SOUL_SEARCH_FALLBACKS=searxng` — if the primary errors or returns nothing, the
  chain advances (and the result notes which provider answered).
- Each provider is one small module implementing the same function; adding one never
  touches the skill's contract, the Researcher, or the Manager.
- Multi-provider is also a *quality* lever: a SearXNG instance alone is already
  multi-engine, strengthening the "independent sources" goal.
- (`fetch-page` gets the same treatment later — a `FetchConnector` for raw HTTP vs a
  headless-browser provider — out of v1 scope.)

**Local-first exception, stated plainly:** research is the one feature whose *purpose*
is to leave the machine. Search queries and fetched URLs go to the internet; the
*reasoning* still happens on local models. `docs/` and README will carry this note,
mirroring how the wake-word privacy trade-off was documented.

The Researcher's flow per command: `web-search(query)` → pick the most promising
`max-sources` results (different domains preferred — "different sources" means
*independent* ones) → `fetch-page` each → compose findings citing each source.

**Computing confidence — signals, not vibes.** A small model's self-rating alone is
not trustworthy, so the Researcher's confidence is its self-assessment **clamped by
hard evidence caps** the worker computes deterministically:

| Evidence | Cap |
| --- | --- |
| 0 sources fetched successfully | ≤ 0.2 |
| 1 source only | ≤ 0.6 |
| ≥ 2 independent sources | ≤ 1.0 |

`confidence = min(model_self_assessment, evidence_cap)` — the model is asked, in its
final compose step, to rate 0–1 *and state whether the sources agree*; disagreement
it reports lowers its own rating. Simple, testable, explainable in logs.

## 5. The delegation flow, end to end

The Manager gets exactly **one** built-in delegation tool — generic, capability-routed
(not a pool skill: delegation is orchestration, so the orchestrator injects it).
It is the same tool whether SOUL has one worker or twelve:

```
delegate {capability: enum[registry.capabilities()], task: string}

  Description (GENERATED from the registry, grouped by provider):
  "Hand a task to a specialist and wait for the result. Pick the capability that
   matches the need:
   - research.search, research.fetch (researcher — finds current, real-world
     information on the web: news, prices, releases, weather, scores. Use for
     anything after your training data.)
   (- task.schedule, email.send, …: appear here automatically when their
      providers register)"
```

Routing is `delegate → registry.whoSupports(capability) → worker` — the Manager
never names an agent. An unknown capability returns a tool error ("no agent
supports: X — available: research.search, research.fetch"), never a crash. The
resolved capability rides in the command payload so the worker knows which part
of its contract was invoked.

```
User: "What's the latest LTS version of Node.js?"

 1. POST /chat → ManagerAgent loop starts (status: thinking)
 2. Model calls delegate{capability: "research.search",
                         task: "latest Node.js LTS version"}
 3. Manager: before_skill hooks (block-secrets) → whoSupports(research.search)
    → researcher → AgentCommand(type: task, target: researcher,
                                payload: {task, capability}) → CommandBus;
    registers a pending future for the command id
    → WS: delegation{from: super, to: researcher, task}
 4. ResearcherWorker picks it up on its executor
    → task.started → WS: researcher status working
    → runs its own model loop:
         web-search → WS: tool.call(agent=researcher, web-search)
         fetch-page ×N → WS: tool.call/tool.result per source (task.progress)
    → task.completed{findings, sources} → WS: researcher status idle
 5. Manager's future completes with a TaskResult{confidence, summary, sources}
    → the CONFIDENCE POLICY (§5.1) runs — possibly one retry —
    then the (final) result returns as the delegate tool's result
 6. Manager model loop continues → ALWAYS SUMMARIZES (persona + tool description
    both instruct: synthesize findings, cite sources, never dump raw research;
    hedge wording when confidence is moderate)
    → tokens stream → task.done
 7. Failure path: task.failed / timeout → tool result "task failed: <reason>"
    → Manager tells the user honestly what it couldn't find out.
```

### 5.1 The confidence policy — deterministic, not model-decided

What to do with a confidence number is **orchestrator code** in the delegate tool
handler — an 8B model shouldn't be trusted to decide retry-vs-hedge-vs-tell. The
model's job is *presentation*; the policy's job is *decision*:

```
result.confidence ≥ hedge-below (0.7)   → pass through: summarize normally, cite sources
retry-below (0.4) ≤ c < hedge-below     → pass through FLAGGED: tool result is prefixed
                                          "(moderate confidence 0.55 — hedge accordingly)"
                                          → the Manager hedges: "I found …, though I
                                          couldn't fully verify it."
c < retry-below (0.4), attempt 1        → RETRY ONCE: new AgentCommand (fresh id,
                                          attempt: 2, excludeDomains: [first attempt's
                                          sources]) → "ask another source"
c < retry-below after retry             → TELL THE USER: tool result carries what little
                                          was found + explicit low confidence; the
                                          Manager says honestly what it couldn't verify —
                                          it NEVER presents low-confidence findings as fact
```

```yaml
soul:
  delegation:
    timeout-seconds: 120
    confidence:
      retry-below: 0.4      # below this → try again with different sources
      hedge-below: 0.7      # below this → answer with explicit uncertainty
      max-retries: 1        # latency honesty: each retry is a full research loop
```

The retry is a *new command* (fresh correlation id), so the UI shows a second
delegation — the user sees SOUL double-checking, which is a feature, not noise.

The Manager stays in its loop during delegation (blocking await with timeout) —
simple, correct for one-user-one-turn; parallel delegation is a later protocol reuse.

## 6. Wire contract additions (SPEC §5.2)

One new WS event type + reuse of existing ones with sub-agent attribution. **The
console already understands all of this** — `delegation` payloads, per-agent status
and tool events were built in the mock era; this feature finally feeds them real data.

| Event | Payload | Emitted when |
| --- | --- | --- |
| `delegation` *(new factory in orchestrator `WsEvent`)* | `{id, from, to, task, attempt}` | Manager issues a task command |
| `delegation.result` *(new)* | `{id, status, confidence?, sources?}` | that delegation reaches a terminal event |
| `agent.status` | `{status, task: <progress label>}` with `agent: researcher` | started / each progress stage (§3.2c) / terminal |
| `tool.call` / `tool.result` | with `agent: researcher` | each search/fetch |

> **Two amendments the console forced.** The mock-era `delegation` payload was
> `{id, from, to, instruction}`; `instruction` became `task` (the spec's name), but `id`
> stayed and is now the **command id** — so the console pairs a delegation with its
> result, and tells attempt 1 from the retry, instead of guessing "the most recent one".
> And `delegation.result` is new: §7.4's sources block needs the confidence and the
> sources to reach the console, and nothing else carried them. `status` omits the
> `task.` prefix; `confidence`/`sources` are absent unless the delegation completed —
> "stopped" and "certain it's nothing" must not render alike.

Plus one new REST route: `POST /api/v1/conversations/{id}/cancel` → cancels the
conversation's active delegation (§3.5). Returns 202; the outcome arrives on the
stream as `task.cancelled` → researcher `agent.status: idle`.

## 7. UI — who's working (goal 4)

Baseline is the face-first layout (voice-and-face.md):

1. **Delegation strip** — while any sub-agent is non-idle, a compact card strip
   appears under the face: agent name, pulsing working dot, and the **live progress
   label** ticking through its stages ("researcher · Reading nodejs.org (1/3)…" —
   §3.2c). Includes a **⏹ stop button** → `POST …/cancel`; after clicking it shows
   "stopping…" until `task.cancelled` lands. Reuses `AgentRail`'s card internals +
   the existing `delegate-flash` animation; disappears when idle.
2. **Face caption** — during delegation the caption mirrors the same progress labels
   ("Searching the web…", "Summarizing findings…") via the existing
   `agent.status → caption` wiring. Saying **"stop"** in wake-word mode hits the same
   cancel route as the button.
3. **Chat dock** — delegation shows as a subtle inline system line
   ("→ researcher: latest Node.js LTS version"); a confidence retry appends a second
   line ("→ researcher (double-checking, other sources)") — visible diligence.
4. **Sources block** — the answer carries an expandable sources list including the
   confidence ("94% · 3 sources") so the user can judge for themselves. It cites the
   **last** attempt's sources: a retried-away first attempt is not what the answer rests on.
5. ~~`agentStore` needs no schema change; `faceStore` none either.~~ **Both needed one:**
   - `agentStore` gained the delegation *result* (id-correlated) and a `turn` list, drained
     onto the answer as it lands — that is what attaches sources to the right message.
   - `faceStore` had a **bug** this feature exposed: `agent.status{idle}` cleared
     `thinking` outright, so the Researcher finishing dropped the face to "Waiting — ask me
     anything" while the Manager was still composing. The face now tracks *who* is busy and
     stays thinking until the last agent stops. A worker's bare "started" (no label) also
     no longer stomps the caption already showing.

## 8. Testing

- **Protocol unit tests**: command routing (target isolation), event correlation,
  timeout → failed, worker executor isolation; registry — `whoSupports` resolution,
  unknown capability, duplicate-provider first-wins + warning.
- **Confidence unit tests**: evidence caps (0/1/2+ sources → ≤0.2/≤0.6/≤1.0;
  self-assessment never raises above the cap); policy table — high passes through,
  moderate flags for hedging, low retries exactly once with `excludeDomains` set,
  low-after-retry hands over honestly.
- **Cancellation tests**: cancel mid-loop → worker stops at the next step boundary,
  emits `task.cancelled`, Manager's future completes as cancelled; timeout issues a
  cancel command; cancelling a finished command is a no-op.
- **Progress tests**: the stub-LLM researcher run asserts the exact stage sequence
  `searching → found → reading (1..N) → summarizing → completed`.
- **Connector tests**: primary provider errors → fallback chain advances; result
  notes the answering provider; all providers behind the same interface (pytest-style
  fixtures in the skill's own tests, offline).
- **`ManagerAgentTest`-style stub-LLM test**: scripted Manager model calls
  `delegate{capability: research.search}`; scripted Researcher model calls `web-search`/`fetch-page`
  (skill scripts stubbed via a test pool dir); asserts the full event sequence —
  `delegation` → researcher `working` → tools → `idle` → Manager summary containing
  the findings — plus the failure/timeout path.
- **Skill smoke tests**: `pooltest.py` gains network-skill handling — `web-search`
  and `fetch-page` declare `example.offline: true` fixtures so CI never hits the
  internet (a `--live` flag runs real queries locally).
- **Console**: dispatcher/agentStore tests already cover `delegation`; add a
  DelegationStrip render test.

## 9. Delivery phases

| Phase | Scope | Exit test |
| --- | --- | --- |
| **1 — Protocol** | `protocol/` package: AgentCommand (`task` + `cancel`) / AgentEvent (5-type lifecycle), both buses, **capability-indexed AgentRegistry**, pending-delegation await, **cancellation + timeout-cancels**, unit tests | Bus + registry + cancel tests green; no behavior change |
| **2 — Loop extraction** | `AgentLoop` factored out of `ManagerAgent`, **with cooperative cancellation checks at step boundaries**; all 32+ existing orchestrator tests still green | Pure refactor, proven by existing suite |
| **3 — Researcher** | `web-search` (**SearchConnector layer**, DDG default + fallback chain) + `fetch-page` + persona skills; `ResearcherWorker` (capabilities, staged progress §3.2c, evidence-capped confidence); generic `delegate` tool + **confidence policy** (§5.1); WS `delegation` factory; cancel REST route | Stub-LLM delegation test passes end-to-end incl. retry-on-low-confidence, the full progress sequence, and mid-task cancel; unsupported capability returns a clean tool error |
| **4 — UI** ✅ | Delegation strip (live stage labels + ⏹ stop) + dock lines + sources block + caption; voice "stop" (bare "stop" works — you shouldn't have to say her name to call her off) | Ask a "latest X?" question; watch stages tick by, hit stop mid-research and see it wind down; Manager summarizes with sources |
| **5 — Live polish** ✅ | Answer-gate + withheld findings + delegate description + timeout tuning; fixed a concurrent-WS-send bug. **Exit test passes — see below.** | "What's the latest Node LTS?" answered correctly with sources; "What is 2+2?" does NOT delegate; a garbage query ("flurbo exchange rate") produces an honest low-confidence answer, not a hallucination |

### What the first live run (llama3.1:8b, 4 GB) actually did

Phase 4's exit test was driven against a real model. The **orchestration worked**: the
Manager delegated by capability, the strip ticked *Searching the web… → Found 6 sources*,
the retry fired and rendered, the answer came back in ~40 s. Two real failures, both
model-behaviour rather than plumbing, both for phase 5:

1. **The Researcher never calls `fetch-page`.** It runs `web-search`, then writes findings
   straight from the snippets. So it reads 0 pages — and the evidence cap did exactly its
   job, refusing to certify snippet-deep findings and pinning confidence at 0.2. The
   machinery is right; the model isn't following the persona's "a snippet is not
   evidence" step. Fix in prompt (and consider: is `web-search` returning snippets rich
   enough that the model *thinks* it's done?).
2. **The Manager presented a low-confidence result as fact.** Handed a tool result reading
   *"(LOW confidence 20% after 2 attempts, 0 sources — do NOT present this as fact…)"* it
   still answered *"The current latest LTS version of Node.js is Node.js 24.16 LTS"* — a
   version that does not exist. This is the anti-hallucination exit test failing outright.
   An 8B model will not reliably obey an instruction buried in a tool result; the hedge
   likely has to move out of the payload's prose and into something structural (a
   `before_respond` gate that can veto an unhedged answer after a low-confidence
   delegation, or a forced hedge prefix the model cannot drop).

The lesson is worth keeping: **the deterministic half of the design held, the model half
did not.** Confidence, evidence caps and the retry policy all behaved; every failure was
the 8B model declining to follow prose. Design accordingly — put the guarantees in code.

### What phase 5 changed, and how the exit test came out

Both failures were fixed by moving the guarantee out of prose and into code:

- **`AnswerGate`** (loop extension point) — a non-streaming agent's final answer can be
  vetoed and the model sent back **once** (once only: a gate it can't satisfy costs one
  step, not the budget). The Researcher's gate refuses an answer written before it has
  read a source. Streaming agents can't use it — by the time a gate sees the answer the
  Manager has already spoken it — which is why the Manager's guarantee is the next one.
- **Low-confidence findings are withheld, not disclaimed.** Below the retry threshold the
  delegate tool hands the Manager *no findings at all*, only the instruction to admit it
  couldn't find out and state no fact. A model cannot parrot evidence it never receives.
- **Delegate tool description now says when NOT to delegate** (arithmetic, timeless facts),
  with the latency called out — so "2+2" is answered directly.
- **Timeout 120 s → 240 s.** Measured: a full gated research loop is ~150 s on the 4 GB
  box (every model call is 20–40 s). 120 s guillotined good research mid-compose.

Exit test, driven through the real console against llama3.1:8b, one fresh conversation
each:

| Prompt | Required | Result |
| --- | --- | --- |
| "latest Node LTS?" | delegates, cites, correct | **"24.11.0"**, cited, hedged "60% · 1 source" ✅ |
| "What is 2+2?" | does NOT delegate | **"4"**, 0 delegations, 7 s ✅ |
| "flurbo→zorkmid rate?" | honest, not invented | **"couldn't find a clear answer"** ✅ |

All three pass. The recency answer is correct where it was a fabrication before — hedged
rather than confident, because the once-only gate gets it from 0 sources to 1, and the
1→2 escalation only fires if the model reports after one *un-nudged* read. That is the
accepted trade-off: the once-only bound on latency matters more than a guaranteed second
read, and a correct-but-hedged answer beats a confident wrong one.

### One real bug this feature introduced — concurrent WebSocket sends

The first two-agent live run died with `IllegalStateException: remote endpoint was in
state [TEXT_PARTIAL_WRITING]`. Until now exactly one thread ever emitted to the socket;
now the Manager streams tokens from its thread while a worker narrates progress from its
executor, and `WebSocketSession.sendMessage` is **not** safe for concurrent use — the two
interleaved mid-frame. Worse, the exception escaped `emit()` and killed the Manager's
turn. Fixed with `ConcurrentWebSocketSessionDecorator` (serializes sends, buffers a slow
client) and by making `emit()` swallow all send failures — a browser that went away must
never take an agent's turn down with it. A regression test drives two threads emitting at
once and fails without the decorator.

## 10. Open questions

1. **Search source** — *architecturally settled by the Connector layer (§4.3)*:
   providers are pluggable, DuckDuckGo ships as the zero-setup default. Remaining
   question is only which fallback to wire first — proposed: an opt-in `soul-search`
   SearXNG compose profile (self-hosted, multi-engine, keeps queries off third-party
   APIs).
2. **Researcher default model** — same-as-Manager (no VRAM swap on 4 GB; proposed)
   vs dedicated small model?
3. **Delegation transcript** — should the raw findings be inspectable in the UI
   (expandable "sources" block under the answer)? Proposed: yes, sources list only.
4. **When NOT to delegate** — prompt-only steering, or a `before_skill` hook that
   vetoes `delegate` calls for obviously-timeless queries? Proposed: prompt-only
   in v1; measure. (An 8B model choosing a *capability* from the tool's enum +
   grouped descriptions needs testing — phase 5's explicit exit test.)
6. **Capability granularity** — the model delegates the *whole* task naming its
   primary capability (`research.search`); the worker uses everything it has. Is
   action-level granularity (`search` vs `fetch`) right for advertisement, or should
   v1 advertise coarser domains (`research.*`)? Proposed: keep action-level entries
   (they cost nothing and future workers like `communication` need them —
   `email.send` ≠ `whatsapp.send`), and teach the tool description that any
   capability of an agent hands the task to that whole agent.
5. Should `task.progress` events throttle (a chatty researcher = noisy UI)?
   Proposed: one event per tool call, no token streaming from sub-agents.
