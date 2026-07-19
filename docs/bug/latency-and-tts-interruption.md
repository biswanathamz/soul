# Bug — high response latency & voice reading interrupted by listening mode

> Reported: field testing on the reference machine (RTX 2050, 4 GB VRAM).
> Status: **RESOLVED** in two rounds — round 1 (model/VRAM + barge-in) and round 2 (the
> real root cause: over-delegation routing). See both Resolution sections at the end.
> Two independent problems surfaced in the same session, documented together.

Asking a plain question ("Tell me something about Mr. Narendra Modi, the PM of India")
took **2–3 minutes**, and while SOUL read the answer aloud she kept flipping into
listening mode and cutting herself off. Neither is a logic bug — both are the local
hardware and the voice defaults interacting badly with the new Researcher feature.

---

## Environment

| | |
| --- | --- |
| GPU | NVIDIA GeForce RTX 2050, **4096 MiB** total (~997 MiB free at rest) |
| Model | `llama3.1:8b` — **4.9 GB** on disk, **5.6 GB** resident with a 4096 context |
| Bound to | both `super` (Manager) and `researcher`, per `application.yml` |

---

## Problem 1 — latency (~2–3 minutes for one answer)

### Symptom

A single question took 5:38 → 5:41 to answer. The question was answerable from the
model's own training data (Modi has been PM since 2014), yet it still ran the full
Researcher loop.

### Evidence

```
$ ollama ps
NAME           SIZE     PROCESSOR          CONTEXT   UNTIL
llama3.1:8b    5.6 GB   50%/50% CPU/GPU    4096      21 seconds from now

$ nvidia-smi --query-gpu=memory.total,memory.free --format=csv
4096 MiB, 997 MiB
```

`50%/50% CPU/GPU` is the whole story.

### Root causes

**A. The model does not fit in VRAM, so half of it runs on the CPU (dominant).**
`llama3.1:8b` needs ~5.6 GB with context; the card has 4 GB. Ollama offloads half the
layers to the CPU, and CPU inference is roughly an order of magnitude slower per token.
This tax is paid on **every** model call — Manager *and* Researcher.

**B. It delegated a question it didn't need to (multiplier).**
"Tell me about Modi, the current PM" reads as *recency* to the model, so it ran the
Researcher (search → fetch → compose) and then re-composed as the Manager. A research
loop is **4–6 sequential model calls**, and each one pays cost A. Six slow calls ≈ the
observed 2–3 minutes. The Manager could have answered this from memory in one call.
(Phase-5 already added "don't delegate timeless facts" to the delegate tool description;
"current PM" still tripped the recency heuristic — this is spec §10 open question #4.)

**C. The model reloads when it goes cold.**
`ollama ps` shows the model about to unload ("21 seconds from now"). The Ollama client
sets no `keep_alive`, so after an idle spell the next question reloads 5.6 GB from disk
before it can even start — extra seconds on top.

### Solutions (in order of impact)

1. **Bind a model that fits 4 GB → runs 100% on GPU.** `llama3.2:3b` (Q4 ≈ 2 GB) fits
   the card entirely. This is the single biggest win: it removes the CPU-offload tax from
   *every* call, Manager and Researcher alike — expect roughly a 4–8× speed-up. Config
   already supports per-agent models (`soul.agents.<name>.model`); the Researcher docs
   already float `llama3.2:3b` for this exact hardware.
   - **Trade-off:** a 3B model is less articulate than 8B. Acceptable here because answer
     *correctness* comes from the tools + evidence caps + confidence policy, not from the
     model's size — the model's job is routing and phrasing.
2. **Delegate less.** Tighten when the Manager reaches for the Researcher so obviously
   stable facts are answered directly. Options: stronger tool-description steering, or a
   lightweight `before_skill` heuristic that vetoes `delegate` for clearly-timeless
   questions. Less reliable than #1 (it depends on model judgement), but when it fires it
   removes the entire ~2-minute loop.
3. **Keep the model warm.** Set a longer `keep_alive` (or `-1`) on the Ollama chat
   requests, so a model already in memory isn't reloaded between questions.

Same-model-for-both stays the default: two *different* models can't co-reside in 4 GB, so
binding Manager and Researcher to the same `llama3.2:3b` avoids a per-delegation VRAM swap.

---

## Problem 2 — reading interrupted by listening mode

### Symptom

While SOUL reads an answer aloud, the face flips to **Listening…** mid-sentence and the
speech is cut off.

### Root cause — barge-in + echo + a fuzzy wake phrase

The relevant defaults (`settingsStore`): `sttEngine: local`, `bargeIn: true`,
`clapWake: true`.

1. **Barge-in keeps the mic open while SOUL speaks**, on purpose — so you can interrupt
   her. In `voice/wakeword.ts`:
   ```ts
   if (v.speaking && !(local && s.bargeIn)) return false; // local + barge-in → stay listening
   ```
2. **Mic capture runs with `noiseSuppression: false`** (`voice/audioCapture.ts`) — needed
   so the clap detector can hear transients. Echo cancellation is on, but neural-TTS audio
   played through speakers is not reliably cancelled.
3. So the open mic hears **SOUL's own voice**, the local STT transcribes it, and the
   **deliberately fuzzy** wake regex —
   ```ts
   /\b(?:hey|hi)[,!.]?\s+(?:soul|seoul|sole|saul|sol)\b.../i
   ```
   — occasionally false-matches a fragment of her own speech.
4. A false match runs `onWake()` → `stopSpeaking()` (cuts the reading) → `startListening()`
   (face → Listening). Exactly the reported behaviour.

### Solution — default barge-in OFF

Flip the `bargeIn` default to `false`. Then, while SOUL speaks, `shouldListen()` returns
`false`, the wake loop pauses, and **the mic is closed during playback** — so her own audio
can't trigger a false wake and reading completes.

- **Trade-off:** you can no longer interrupt her mid-sentence by voice. You *can* still
  wake her the instant she stops, and the Stop button / Esc still work immediately.
- Barge-in stays available as an opt-in in Settings for anyone on headphones (where there
  is no speaker→mic echo path).

Optional hardening if barge-in is kept on later: suppress wake matching for a short window
around TTS, or require a stricter wake phrase while speaking.

---

## Proposed change set

| # | Change | Where | Risk |
| --- | --- | --- | --- |
| 1 | Bind Manager + Researcher to `llama3.2:3b` (pull it, rebind config) | `application.yml`, `models.yaml` | Model quality trade-off; easily reverted |
| 2 | `keep_alive` on Ollama chat requests | `OllamaHttpClient` | Low |
| 3 | Default `bargeIn: false` | `settingsStore` | Low; opt-in preserved |
| 4 | *(optional)* reduce over-delegation of timeless facts | delegate tool desc / `before_skill` hook | Model-dependent |

Item **1** is the dominant latency fix; item **3** fully resolves the reading
interruption. Items 2 and 4 are incremental.

---

## Resolution

All four changes were applied.

| # | Change | Files |
| --- | --- | --- |
| 1 | Manager + Researcher bound to `llama3.2:3b`; `8b` kept as an optional spare | `application.yml`, `soul-scripts/ollama/models.yaml` |
| 2 | `keep_alive` (default `30m`) sent on every chat request | `SoulProperties`, `OllamaHttpClient` |
| 3 | `bargeIn` default → `false`, with a persist migration that turns it off once for existing testers | `settingsStore.ts`, `SettingsDrawer.tsx` |
| 4 | Delegate tool description: "well-known people/places/history — answer from memory even when asked as 'current'" | `DelegateTool.java` |

### Verified

`llama3.2:3b` measured directly on the reference box:

```
$ ollama ps
NAME          SIZE     PROCESSOR   CONTEXT   UNTIL
llama3.2:3b   2.6 GB   100% GPU    4096      29 minutes from now
```

- **100% GPU** (was 50% CPU) — the whole model fits the 4 GB card.
- **~42 tok/s** on a short generation (was single digits under CPU offload).
- **"29 minutes"** until unload confirms `keep_alive: 30m` is being sent (was ~seconds).
- It answered "the Prime Minister of India is Narendra Modi" **from memory in 0.7 s** — the
  exact question that took minutes before, and one it should never have delegated.

Tests: orchestrator 100 (added `OllamaHttpClientTest` proving `keep_alive` is on the wire),
console 98, `manage.py verify` OK. Item 4 is a best-effort prompt nudge — the reliable win
is item 1, which speeds up every call whether or not the model delegates.

### To pick up the fix on a running stack

`make up` (rebuilds + force-recreates the containers so the orchestrator loads the new
`application.yml` and the console ships the new default). The `llama3.2:3b` pull is already
done on the host; `make models-sync` provisions it from the manifest on a fresh machine.
Existing browsers apply the barge-in change automatically via the settings migration.

---

## Resolution — round 2 (the real root cause: routing, not token speed)

The round-1 model swap fixed *per-call* speed (100 % GPU, 42 tok/s) but a plain
**"Who is the Prime Minister of India?"** still took **97 s and then refused to answer**
("I couldn't find any real-time information… check news sources") — a fact that has been
true since 2014 and sits in the model's training data. Field logs told the whole story:

```
answer gate sent researcher back: "You have not opened a single result… fetch-page…"
researcher gave no CONFIDENCE line — treating as 0.5
```

Three faults compounded, and none was token speed:

1. **The 3B Manager over-delegates.** It handed "who is the PM" to the Researcher instead
   of answering from memory. The round-1 prompt nudge (fix #4) does not hold on a 3B model —
   it reads "PM of India" next to a tool that says "delegate current facts" and delegates.
2. **The answer gate doubled the loop.** Every delegated query was vetoed once ("you haven't
   opened any results") and forced through a full fetch cycle — ~40–60 s of the 97 s.
3. **The withhold rule then backfired.** When the Researcher couldn't nail a "current" fact
   it never should have been asked, the low-confidence branch *forbids the Manager from using
   its own memory* — so it refused a fact it actually knew. And because a 3B's confidence is
   stochastic, the same question gave a wrong "President Droupadi Murmu (50 %)" on one run and
   a flat refusal on the next.

The honest conclusion: **on a 4 GB box a 3B model cannot reliably tell "a fact I know" from
"needs live data," and the research pipeline is slow enough that every misroute costs ~1.5 min
and often a wrong or withheld answer.** Speeding up tokens can't fix a question that should
never have been delegated.

### Changes

| # | Change | Where |
| --- | --- | --- |
| 5 | **`DelegationGuard`** — a deterministic backstop: clearly-timeless question shapes (heads of state, capitals, historical dates, who-made-what, definitions) with no recency marker never reach a worker, whatever the model tries. High-precision: any recency signal (`latest`, `price`, `today`, a recent year…) lifts the veto entirely. | `DelegationGuard.java`, wired into `DelegateTool.handle()` |
| 6 | **Rebalanced the delegate tool description.** The guard now owns "don't over-delegate known facts", so the prose is free to push *harder* toward delegating genuinely time-sensitive queries (latest/current versions, prices, news) — fixing the opposite failure where the model answered "latest Node.js LTS" wrongly from memory. | `DelegateTool.description()`, `skillpool/persona/prompt.md` |
| 7 | **Relaxed the evidence gate: `research.min-sources` 2 → 1.** One opened page instead of two removes the second fetch-and-summarize cycle (~40 s on this box); a lone source is still capped at ≤0.6 confidence and hedged, so honesty is preserved by the policy, not the loop. | `SoulProperties.Research`, `application.yml`, `ResearcherWorker` |

Why not simply weaken the withhold rule (fault 3)? Because for a genuinely-volatile fact that
research *couldn't* verify, refusing is the correct behaviour — weakening it reopens the
fabricated-version bug (§9). The durable fix is routing: once the guard keeps well-known facts
out, anything that still reaches withhold genuinely could not be verified.

### Verified (live, on the reference box)

| Question | Before | After |
| --- | --- | --- |
| "Who is the Prime Minister of India?" | 97 s → *refused* | **3 s → "Narendra Modi"** (guard blocked the delegation; answered from memory) |
| "who is the **current** PM of India" | ~2–3 min → wrong/refused | guard still blocks it — logs: `refused by the guard: who is the current Prime Minister of India` |
| "What is the latest LTS version of Node.js?" | answered **wrongly** from memory ("18.4.0") | **delegated** (correct), ~23 s, and honestly "couldn't verify" rather than fabricating |

Tests: orchestrator **129** (was 100) — adds `DelegationGuardTest` (a table pinning both what
it blocks *and* the time-sensitive questions it must not block) and a `min-sources: 1` gate
test; console 98; `manage.py verify` OK.

**Known limitation.** Routing on a 3B model is now reliable at the *extremes* the guard covers,
but the fuzzy middle ("is the CEO of X still Y?") still rests on the model's judgement. The
guard is deliberately high-precision — it only vetoes shapes that are essentially always
timeless — so it will never turn a genuinely-current question into a stale answer; it just
won't catch every possible over-delegation. Search *quality* (a weak DuckDuckGo result set can
still yield an honest "couldn't verify") is a separate axis, tracked with the SearchConnector
work.
