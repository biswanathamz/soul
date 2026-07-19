# Bug — spoken proper nouns are transcribed phonetically ("OpenAI" → "open your eyes")

> Reported: field testing on the reference machine, voice input.
> Status: **RESOLVED** — see Resolution.

Asked aloud to *"Check OpenAI's official blog and tell me the latest blogs"*, SOUL answered
with a summary of a Christian devotional site: 200+ free Sunday school lessons, a sermon
titled "Jesus Opens our Eyes". Nothing in the orchestrator misbehaved. The transcript it
was given read:

> "Check **open your eyes** official blog and tell me the **lesson** blogs"

`OpenAI` → "open your eyes", `latest` → "lesson". SOUL then researched *that* faithfully
and reported it accurately. The failure was entirely upstream, in the ears.

---

## Root cause — three speed-first STT settings compounding

`soul-voice/app.py` transcribed with faster-whisper like this:

```python
segments, _info = model.transcribe(
    io.BytesIO(audio), language="en", beam_size=1, vad_filter=True
)
```

1. **`base.en`** — the second-smallest Whisper (74M params). Proper nouns are its weakest area.
2. **`beam_size=1`** — greedy decoding. Once it commits to "open your", the rest follows.
3. **No `initial_prompt`** — Whisper had *no* vocabulary bias, so an unknown brand name is
   rendered phonetically. "OpenAI" → "open your eyes" is the textbook case.

Whisper's `initial_prompt` is decoder context, not a filter: it biases spelling toward the
terms it names without forbidding anything else. Nothing was telling it these words existed.

---

## What the measurements actually said

Piper TTS generated the sentence; the clip was then degraded to three SNRs and a quiet
variant to approximate mic conditions. Each clip was decoded by both models under three
configurations (`OK` = "OpenAI" survived):

| clip | model | beam1, no prompt | beam1 + prompt | beam5 + prompt |
| --- | --- | --- | --- | --- |
| clean | base.en | OK / 0.6 s | OK / 0.6 s | OK / 0.7 s |
| clean | small.en | **MISS** / 1.7 s | OK / 1.6 s | OK / 1.8 s |
| SNR 20 | base.en | **MISS** / 0.6 s | OK / 0.6 s | OK / 0.6 s |
| SNR 20 | small.en | **MISS** / 2.0 s | OK / 1.6 s | OK / 1.9 s |
| SNR 10 | base.en | OK / 0.6 s | OK / 0.5 s | OK / 0.7 s |
| SNR 10 | small.en | **MISS** / 1.5 s | OK / 1.6 s | OK / 1.8 s |
| SNR 5 | base.en | **MISS** / 0.6 s | OK / 0.6 s | OK / 0.7 s |
| SNR 5 | small.en | **MISS** / 1.6 s | OK / 1.6 s | OK / 1.9 s |
| quiet | base.en | OK / 0.6 s | OK / 0.6 s | OK / 0.8 s |
| quiet | small.en | **MISS** / 2.1 s | OK / 2.2 s | OK / 2.1 s |

- **The prompt fixes it in 10/10 cases.** Without it, 6 of 10 mis-transcribe.
- **`small.en` was never more accurate than `base.en`** here, and ran **2.5–3.5× slower**.
  It was *worse* on the clean clip, where `base.en` got it right unaided.
- **`beam_size=5` changed no outcome once the prompt was present**, and without a prompt it
  actively *hurt* (base.en clean: correct at beam 1, "open AI" at beam 5) — beam search
  picked the more "natural" word split absent any hint the brand exists.

---

## Resolution

| # | Change | Where |
| --- | --- | --- |
| 1 | **`initial_prompt`** naming the brands SOUL is actually asked about (OpenAI, Anthropic, Claude, GitHub, Ollama, Node.js…), overridable via `STT_PROMPT` | `app.py` |
| 2 | **`beam_size` 1 → 5**, via `STT_BEAM_SIZE` | `app.py` |
| 3 | **`STT_MODEL` left at `base.en`** — deliberately *not* upgraded | `Dockerfile`, `app.py` |

Item 1 does the work. Item 2 is kept as cheap insurance (~0.1 s on `base.en`) for real speech,
where this synthetic test is weakest — but it is not what fixed the bug.

**Item 3 was planned as an upgrade to `small.en` and reverted on the evidence.** It never won
a single case and cost 2.5–3.5× the latency, and latency is precisely what
[the previous bug](latency-and-tts-interruption.md) was about. It remains one flag away:

```
--build-arg STT_MODEL=small.en      # bake it into the image
STT_MODEL=small.en                  # or override at runtime
```

### Limits of this verification

The audio is **synthetic** (Piper, US female) with **synthetic** noise. It never reproduced
the original failure — `base.en` transcribed the clean clip correctly with the old settings,
whereas the live failure came from a real human voice. So this test cannot speak to accent,
disfluency, or room acoustics, which is exactly where a larger model would be expected to
earn its cost. If the mishearing persists on real speech, `small.en` is the first thing to
try, and the A/B above is worth re-running on a recording of the actual voice.

`initial_prompt` also *biases*, it does not guarantee: it will hold "OpenAI" reliably because
that word is named explicitly, but an unusual proper noun outside the list can still be
rendered phonetically. Extend `STT_PROMPT` as new vocabulary shows up.
