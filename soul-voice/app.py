"""soul-voice — SOUL's local speech microservice (docs/voice-and-face.md §4.1, §4.3).

TTS: FastAPI wrapper around Piper. STT: faster-whisper (phase 4 — fully local ears).
Stateless; models are loaded lazily on first use and cached. Each engine is
serialized with its own lock — the sessions aren't thread-safe, and
one-at-a-time keeps CPU sane next to Ollama.
"""

from __future__ import annotations

import io
import os
import threading
import wave
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field

DEFAULT_VOICE = os.environ.get("DEFAULT_VOICE", "en_US-amy-medium")

# Known Piper voice metadata (best-effort; unknown voices still work).
_GENDER = {"amy": "female", "alba": "female", "lessac": "female", "kristin": "female"}

app = FastAPI(title="soul-voice", version="0.1.0")

_voices: dict[str, object] = {}
_synth_lock = threading.Lock()


def voices_dir() -> Path:
    return Path(os.environ.get("VOICES_DIR", "voices"))


def available_voices() -> list[str]:
    return sorted(p.stem for p in voices_dir().glob("*.onnx"))


def _load(voice_id: str):
    """Load (and cache) a Piper voice. Import is lazy so tests can stub this out."""
    model = voices_dir() / f"{voice_id}.onnx"
    if not model.exists():
        raise HTTPException(status_code=404, detail=f"unknown voice '{voice_id}'")
    if voice_id not in _voices:
        from piper import PiperVoice  # heavy import — only when actually synthesizing

        _voices[voice_id] = PiperVoice.load(str(model))
    return _voices[voice_id]


def _synthesize(voice, text: str, wav_file: wave.Wave_write, speed: float | None) -> None:
    """Bridge both Piper APIs (1.2 `synthesize`, 1.3+ `synthesize_wav`)."""
    length_scale = None if speed is None else 1.0 / speed
    if hasattr(voice, "synthesize_wav"):  # piper >= 1.3
        kwargs = {}
        if length_scale is not None:
            try:
                from piper import SynthesisConfig

                kwargs["syn_config"] = SynthesisConfig(length_scale=length_scale)
            except Exception:  # speed unsupported on this version — speak at 1.0
                pass
        voice.synthesize_wav(text, wav_file, **kwargs)
    else:  # piper 1.2
        if length_scale is not None:
            voice.synthesize(text, wav_file, length_scale=length_scale)
        else:
            voice.synthesize(text, wav_file)


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    voice: str | None = None
    speed: float | None = Field(default=None, ge=0.5, le=3.0)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "voices": available_voices()}


@app.get("/api/v1/voices")
def voices() -> list[dict]:
    out = []
    for vid in available_voices():
        parts = vid.split("-")
        name = parts[1] if len(parts) > 1 else vid
        out.append(
            {
                "id": vid,
                "lang": parts[0].replace("_", "-") if parts else "",
                "gender": _GENDER.get(name),
                "default": vid == DEFAULT_VOICE,
            }
        )
    return out


# ---------------------------------------------------------------------------
# STT — faster-whisper (fully local ears, phase 4)
# ---------------------------------------------------------------------------

_stt = None
_stt_lock = threading.Lock()


def _load_stt():
    """Load (and cache) the whisper model. Lazy so tests can stub this out."""
    global _stt
    if _stt is None:
        from faster_whisper import WhisperModel  # heavy import — only when transcribing

        _stt = WhisperModel(
            os.environ.get("STT_MODEL", "base.en"),
            device="cpu",
            compute_type="int8",
            download_root=os.environ.get("STT_DIR", "stt"),
        )
    return _stt


@app.post("/api/v1/stt")
async def stt(request: Request) -> dict:
    """Transcribe an audio clip (the console posts 16 kHz mono WAV). → {text}."""
    audio = await request.body()
    if not audio or len(audio) < 44:  # smaller than a WAV header — nothing to hear
        raise HTTPException(status_code=400, detail="no audio")
    model = _load_stt()
    with _stt_lock:
        # vad_filter drops non-speech windows — silence and noise come back as "".
        segments, _info = model.transcribe(
            io.BytesIO(audio), language="en", beam_size=1, vad_filter=True
        )
        text = " ".join(s.text.strip() for s in segments).strip()
    return {"text": text}


@app.post("/api/v1/tts")
def tts(req: TtsRequest) -> Response:
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text is empty")
    voice = _load(req.voice or DEFAULT_VOICE)

    buf = io.BytesIO()
    with _synth_lock:
        with wave.open(buf, "wb") as wav_file:
            _synthesize(voice, text, wav_file, req.speed)
    return Response(
        content=buf.getvalue(),
        media_type="audio/wav",
        headers={"Cache-Control": "no-store"},
    )
