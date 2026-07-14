# soul-voice

Local speech microservice for SOUL — a natural, polite **voice** (Piper TTS) and fully
local **ears** (faster-whisper STT). FastAPI, port **7789**, CPU-only (never competes
with Ollama for the GPU). All models baked into the image — works offline.
Design: `docs/voice-and-face.md` §4.1, §4.3.

## API

| Route | What |
| --- | --- |
| `GET /health` | `{status: "UP", voices: [...]}` |
| `GET /api/v1/voices` | Available voices: `[{id, lang, gender, default}]` |
| `POST /api/v1/tts` | `{text, voice?, speed?}` → `audio/wav` (mono 22 kHz) |
| `POST /api/v1/stt` | body: 16 kHz mono WAV → `{text}` (whisper `base.en`, VAD-filtered) |

The console reaches it same-origin via the `/voice/*` proxy (Vite in dev, nginx in
the container), so browser code calls `POST /voice/api/v1/tts`.

## Voices

Baked into the image at build time (`ARG VOICES`, default Amy + Alba):

- `en_US-amy-medium` — natural US female (**default**)
- `en_GB-alba-medium` — Scottish female (alternative)

Switch per-request (`voice` field), per-deploy (`DEFAULT_VOICE` env), or in the
console's Settings drawer.

## Dev & tests

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest                 # contract tests — Piper is stubbed, no models needed
```

Run for real (needs `pip install -r requirements.txt` + models in `voices/`):

```bash
uvicorn app:app --port 7789
curl -s localhost:7789/api/v1/tts -X POST -H 'content-type: application/json' \
  -d '{"text":"Hello, I am SOUL."}' -o hello.wav && aplay hello.wav
```
