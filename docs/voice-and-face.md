# SOUL — Voice & Face ("Presence") Design

> Branch: `feature/voice-face` · Status: **draft for review** · Depends on: SPEC.md §5 (wire contracts), ui-manager-integration.md, TDD-soul-console.md
>
> SOUL stops looking like a chatbot and starts feeling like a presence: a face at the
> center of the screen that listens when you call it — "**Hey SOUL**" — thinks, speaks
> back in a natural, polite, feminine voice, and shows how it feels. Chat becomes a
> small collapsible dock, not the main event.

---

## 1. Goals

1. **Voice in, voice out.** Talk to SOUL hands-free: wake it with "Hey SOUL" / "Hi SOUL",
   speak your request, hear the answer spoken back.
2. **A natural, polite, feminine voice.** Neural TTS (not the robotic OS `speechSynthesis`),
   warm and courteous phrasing via the persona.
3. **Manager-only, unchanged.** The user still talks to exactly one agent — the Manager.
   Voice is a new *transport* into the same `POST /chat` + `/ws/stream` contract. No new
   agent, no new orchestrator endpoints.
4. **Face-first UI.** The center of the screen is SOUL's animated face, showing:
   *waiting (idle), listening, thinking, speaking, good mood, bad mood*. The chat
   transcript lives in a **collapsed dock** — expand it only when you want it.
5. **Local-first, still.** TTS runs locally (new `soul-voice` microservice). No cloud voice
   APIs on the answer path. (One pragmatic v1 exception on the input side — see §5.1.)

## 2. Non-goals (v1)

- Speaker identification / multi-user voice profiles.
- Precise lip-sync or a 3D avatar — the face is stylized 2D (yellow-on-black, JARVIS-esque).
- Custom wake-word phrases beyond "hey/hi soul".
- Emotion detection from the user's *tone of voice* (mood is driven by system events, §4.3).
- Mobile/PWA voice.

---

## 3. UX — the new layout

```
┌──────────────────────────────────────────────────────────────┐
│  SOUL ●online                                    ⚙ settings  │
│                                                              │
│                                                              │
│                        ╭──────────╮                          │
│                       (   ◠   ◠   )      ← the face:        │
│                       (     ‿     )        center stage,     │
│                        ╰──────────╯        animated states   │
│                                                              │
│                    “Listening…”  ← caption under the face    │
│                                                              │
│                                                              │
│   🎙 push-to-talk                     ┌────────────────────┐ │
│                                       │ 💬 chat  (3)   ⌃   │ │  ← collapsed dock
└──────────────────────────────────────┴────────────────────┴─┘
```

- **Face** (`SoulFace`) replaces the orb as the centerpiece — an evolution of the existing
  `SoulOrb`: a minimal geometric face (eyes + mouth on the glowing disc), yellow/black theme.
- **Chat dock** (`ChatDock`): a collapsed pill at the bottom-right showing the latest
  message snippet + unread count. Click (or `⌘/Ctrl+K`) expands it into the existing
  `ChatPanel` (side sheet). Composer is available in both states — typing always works.
- **Caption line** under the face mirrors what SOUL is doing ("Listening…", "Thinking…",
  the spoken sentence as it's said — doubles as **captions** for accessibility).
- Mic button stays (push-to-talk always works); a **persistent mic indicator** shows
  whenever the microphone is live (wake-word mode) — never listen silently.
- `prefers-reduced-motion`: face animations drop to gentle cross-fades.

### 3.1 Face states

Two independent layers — **activity** (what SOUL is doing) and **mood** (how it feels).
This keeps the state machine small and testable.

**Activity** (exactly one at a time):

| State | Trigger | Face rendering |
| --- | --- | --- |
| `idle` (waiting) | default / turn finished | slow breathing glow, occasional blink, soft gaze |
| `listening` | wake word heard or mic tapped | eyes widen, pulsing ring synced to mic level |
| `thinking` | request sent; `agent.status: thinking/working` | eyes narrow/look up, orbiting particles |
| `speaking` | TTS audio playing | mouth animates with audio amplitude |

**Mood** (a modifier over any activity; decays back to `neutral`):

| Mood | Trigger | Face rendering |
| --- | --- | --- |
| `neutral` | default | standard yellow glow |
| `pleased` (good) | `task.done` (successful turn) | brighter warm glow, brief smile, ~4 s decay |
| `concerned` (bad) | `error` event, blocked skill, WS offline | dimmer/amber tint, flatter mouth, furrowed brow; clears on next successful activity |

Priority when events overlap: `listening` > `speaking` > `thinking` > `idle`; mood renders
as tint/expression on top of whichever activity is showing.

### 3.2 The state machine

```
                 wake word / mic tap
        ┌──────────────────────────────────┐
        ▼                                  │
   ┌─────────┐  final transcript   ┌──────────┐
   │listening│ ───────────────────▶│ thinking │◀─── tool.call / agent.status:working
   └─────────┘   (POST /chat)      └──────────┘
        ▲                                │ first spoken sentence ready
        │ wake word (barge-in, later)    ▼
        │                          ┌──────────┐
   ┌─────────┐   audio queue empty │ speaking │
   │  idle   │◀────────────────────└──────────┘
   └─────────┘    + task.done seen
        ▲
        └── error / cancel from any state (+ mood → concerned)
```

---

## 4. Architecture

One new microservice, no orchestrator changes:

```
 mic ─▶ wake word ─▶ STT ─▶ soul-console ──POST /api/v1/chat──▶ soul-orchestrator ──▶ Ollama (host)
                             │   ▲                                    │
                             │   └────────── /ws/stream events ◀──────┘
                             │  (token, agent.status, task.done, error → face + captions)
                             │
                             └──POST /voice/api/v1/tts──▶ soul-voice (:7789, Piper TTS)
                                        ◀── audio/wav ────┘
```

| Piece | Change |
| --- | --- |
| `soul-voice` | **NEW** Python microservice (FastAPI + [Piper](https://github.com/rhasspy/piper) neural TTS), port **7789**, containerized (CPU is fine — Piper is faster than real-time). |
| `soul-console` | Face-first layout, `faceStore` state machine, wake-word listener, TTS playback queue, chat dock. |
| `soul-orchestrator` | **No changes.** Same REST + WS contract. (Requirement 3 already holds: `/chat` only ever reaches the Manager.) |
| Persona (`skillpool/persona`) | Updated: polite, warm, feminine persona; concise spoken-style answers. |

Port map becomes: console `7787` · orchestrator `7788` · **voice `7789`** · Ollama `11434`.
Same-origin: Vite/nginx proxy `/voice/*` → `soul-voice:7789` (prefix stripped), like `/api` and `/ws`.

### 4.1 `soul-voice` — the TTS service

- **Engine: Piper** — local neural TTS, natural voices, ~1× real-time on CPU, no GPU
  contention with Ollama. Default voice: **`en_US-amy-medium`** (natural female);
  alternates configurable (`en_GB-alba-medium`, `en_US-lessac-medium`). Voice models are
  ONNX files (~60 MB) fetched at image build into a named volume.
- **API:**
  - `GET /health` → `{status: "UP"}`
  - `GET /api/v1/voices` → `[{id, lang, gender, default}]`
  - `POST /api/v1/tts` `{text, voice?, speed?}` → `200 audio/wav` (mono 22 kHz)
- Stateless; concurrency limited (queue) so parallel sentence requests don't thrash CPU.

### 4.2 Speaking the answer — sentence-chunked TTS

Waiting for the full answer before speaking is unacceptable at our generation speed
(~30 s+ per answer on this GPU). So the console speaks **as sentences complete**:

1. Buffer `token` events; on sentence boundary (`.`, `?`, `!`, newline — with guards for
   abbreviations/numbers), dispatch that sentence to `/tts`.
2. An **ordered audio queue** plays results strictly in sentence order (fetch in parallel,
   play sequentially). First sentence usually starts within a few seconds of generation.
3. `speaking` state runs until the queue drains *and* `task.done` has arrived.
4. Feature flag `voice.chunked` (default on); off = single TTS call on `task.done`.
5. Code blocks / tables in the answer are **not** read aloud — spoken form says
   "I've put the code in the chat" (dock badge pulses); captions show full text.

### 4.3 Voice input — wake word + STT

**Wake word ("Hey SOUL" / "Hi SOUL") — v1 approach:**
continuous `SpeechRecognition` (existing `stt.ts` machinery) running in low-cost mode,
matching final+interim transcripts against `/\b(hey|hi),?\s+soul\b/i`. On match: chime,
face → `listening`, start utterance capture; auto-restart the recognizer when Chrome
times it out (~60 s / on silence).

- *Trade-off, stated honestly:* Chrome's Web Speech API sends audio to Google's recognizer —
  an exception to local-first **on the input side only**, so wake-word mode is **off by
  default** and the engine choice is labeled in Settings. Push-to-talk
  and typing remain fully local paths to a local answer.
- *Phase 4 (implemented) — fully local ears:* `soul-voice` gained `POST /api/v1/stt`
  (faster-whisper, `base.en` int8, baked into the image so it works offline). The console
  captures mic PCM (16 kHz mono WAV; `noiseSuppression` OFF — Chrome's suppressor erases
  clap transients; echo-cancellation stays on for barge-in), endpoints utterances with an
  RMS/VAD state machine, and transcribes locally. **The speech gate is adaptive**: it
  tracks the ambient noise floor (drops fast, drifts up slowly) and triggers at ~3.5×
  above it, clamped to [0.006, 0.05] — fixed thresholds failed on real mics (Chrome's
  SpeechRecognition applies gain that raw `getUserMedia` audio doesn't get). **Local is
  the default engine**; browser Web Speech remains selectable.
  - *Wake word, local:* rather than a WASM keyword engine (no pretrained "hey soul"
    openWakeWord model exists, and Porcupine needs an account key), the local path does
    **VAD-gated utterance spotting through the same whisper endpoint** — short utterances
    (≤4 s) are transcribed and run through the same `matchWake`. One engine, zero cloud,
    passes the phase-4 exit test. A dedicated WASM keyword engine stays a future option
    if idle-CPU cost ever matters.
  - *Barge-in (implemented):* with the local engine, the ear stays open while SOUL speaks —
    saying "Hey SOUL" silences her and takes the new request. Toggle in Settings (default on).
  - *Triple-clap wake (implemented):* 👏👏👏 wakes SOUL exactly like her name — a pure-DSP
    transient detector (`ClapDetector`) on the same PCM stream, everything relative to the
    tracked noise floor (no absolute thresholds — mic volumes vary wildly). A clap = an
    energy spike ≥5× floor, impulse-shaped (raw 48 kHz peak ≫ frame RMS — measured before
    the averaging downsampler smears it), that **decays below 30% of its own spike height**
    within ~3 frames. That last rule is reverb-aware: real rooms + AGC hold a clap's tail
    far above the noise floor, so "fell back to silence" was the wrong test (found live).
    Sustained sound (speech, music) fails the decay test and is discarded. Three claps
    within ~1.5 s gaps → same flow as the wake word. No whisper call involved, so it also
    works mid-transcription. Local engine only (the browser engine exposes no PCM).
    Toggle in Settings (default on).

**Utterance capture:** after wake, existing STT flow (same as mic tap) with silence-based
end-pointing (~1.2 s), then normal `POST /chat`.

**Modes** (Settings): `off` (type only) · `push-to-talk` (default today) ·
`wake word` (hands-free) — plus voice picker, speech rate, replies-aloud toggle.

### 4.4 Console state

- **NEW `faceStore`** — pure reducer over `{activity, mood, caption}`; inputs are voice
  pipeline events + WS events via the existing `dispatcher.ts`. (Pure = easily unit-tested.)
- `voiceStore` grows: `wakeWordEnabled`, `micLive`, `speakingQueue`, selected voice/rate.
- `uiStore` grows: `chatDockExpanded`, unread count.
- Mapping into `faceStore` (single source of truth for the face):
  `agent.status thinking/working → thinking` · first queued audio plays → `speaking` ·
  `task.done → mood pleased` · `error`/WS offline → `mood concerned` · STT active → `listening`.

---

## 5. Persona — the "polite, natural" half of the voice

The voice's *character* is prompt work, not audio work. Update `skillpool/persona/prompt.md`:
SOUL is warm, courteous, lightly conversational ("Of course — one moment…"), addresses the
user directly, keeps spoken answers short (2–4 sentences) with detail deferred to the chat
dock. Applies to text too — one consistent personality. (Later refinement: a `before_model`
hook hint when the turn was voice-initiated, so voice replies bias even shorter — open q. #4.)

---

## 6. Delivery phases

| Phase | Scope | Exit test |
| --- | --- | --- |
| **1 — A voice** | `soul-voice` service (Piper, container, proxy) + console playback with sentence-chunked queue + persona rewrite | Ask by text; hear the answer in Amy's voice as it generates |
| **2 — A face** | `SoulFace` (activity+mood renderer), `faceStore`, chat dock collapse/expand, captions | All six states visibly driven by a real turn incl. an error |
| **3 — A name** | Wake word (Web Speech spotting) + hands-free loop + mic indicator + chime | Say "Hey SOUL, what time is it?" hands-free; hear the time |
| **4 — Fully local ears** | `faster-whisper` STT on `soul-voice` (+ local wake spotting via the same endpoint), engine picker, barge-in | Same as 3 with Wi-Fi to Google blocked |

Each phase is independently shippable; 1 and 2 can proceed in parallel.

## 7. Testing

- `faceStore` reducer: exhaustive event→state unit tests (the six states + priorities + decay).
- Sentence splitter + audio queue ordering: unit tests (abbreviations, code blocks, out-of-order fetch completion).
- `soul-voice`: pytest contract tests (`/tts` returns valid WAV; `/voices` lists default) — CI job `verify-voice`.
- E2E (verify skill): drive a text turn → assert `/tts` was hit and `speaking` state reached; force an error → `concerned`.

## 8. Open questions

1. **Default voice** — `amy` (US) vs `alba` (GB)? Decide by ear once `soul-voice` runs (`GET /voices` + samples).
2. **Face art** — evolve `SoulOrb` (eyes/mouth on the disc — recommended, keeps brand) vs a new full-face illustration?
3. **Wake-word default-off OK?** (privacy trade-off in §4.3 until Phase 4.)
4. Should voice-initiated turns hint the model to answer shorter (persona vs `before_model` hook)?
5. Barge-in (interrupt SOUL while speaking) — Phase 3 or 4?
6. Read *every* reply aloud, or only voice-initiated turns? (proposed: only voice-initiated; toggle in Settings.)
