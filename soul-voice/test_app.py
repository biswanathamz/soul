"""Contract tests for soul-voice. Piper is stubbed — no models, no onnxruntime needed."""

import wave

import pytest
from fastapi.testclient import TestClient

import app as appmod


class FakePiper12:
    """Mimics piper 1.2: synthesize(text, wav_file, **kw) writes frames itself."""

    def __init__(self):
        self.calls = []

    def synthesize(self, text, wav_file, **kwargs):
        self.calls.append((text, kwargs))
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(22050)
        wav_file.writeframes(b"\x00\x00" * 220)


class FakePiper13(FakePiper12):
    """Mimics piper 1.3: synthesize_wav instead of synthesize."""

    def synthesize_wav(self, text, wav_file, **kwargs):
        self.synthesize(text, wav_file, **kwargs)


@pytest.fixture
def client():
    return TestClient(appmod.app)


def test_health_is_up(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "UP"


def test_tts_returns_playable_wav(client, monkeypatch, tmp_path):
    fake = FakePiper12()
    monkeypatch.setattr(appmod, "_load", lambda vid: fake)
    r = client.post("/api/v1/tts", json={"text": "Hello, I am SOUL."})
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/wav")
    assert r.content[:4] == b"RIFF"
    # And it parses as a real WAV.
    import io

    with wave.open(io.BytesIO(r.content)) as w:
        assert w.getframerate() == 22050
        assert w.getnframes() > 0
    assert fake.calls[0][0] == "Hello, I am SOUL."


def test_tts_speed_maps_to_length_scale_on_piper12(client, monkeypatch):
    fake = FakePiper12()
    monkeypatch.setattr(appmod, "_load", lambda vid: fake)
    r = client.post("/api/v1/tts", json={"text": "Quickly now.", "speed": 2.0})
    assert r.status_code == 200
    assert fake.calls[0][1] == {"length_scale": 0.5}


def test_tts_works_with_piper13_api(client, monkeypatch):
    fake = FakePiper13()
    monkeypatch.setattr(appmod, "_load", lambda vid: fake)
    r = client.post("/api/v1/tts", json={"text": "New API."})
    assert r.status_code == 200
    assert r.content[:4] == b"RIFF"


def test_tts_rejects_blank_text(client, monkeypatch):
    monkeypatch.setattr(appmod, "_load", lambda vid: FakePiper12())
    assert client.post("/api/v1/tts", json={"text": "   "}).status_code == 400
    assert client.post("/api/v1/tts", json={"text": ""}).status_code == 422  # pydantic min_length


def test_tts_unknown_voice_is_404(client, monkeypatch, tmp_path):
    monkeypatch.setenv("VOICES_DIR", str(tmp_path))  # empty dir — no voices at all
    r = client.post("/api/v1/tts", json={"text": "hi", "voice": "nope"})
    assert r.status_code == 404


def test_voices_lists_models_with_default_flag(client, monkeypatch, tmp_path):
    (tmp_path / "en_US-amy-medium.onnx").touch()
    (tmp_path / "en_GB-alba-medium.onnx").touch()
    monkeypatch.setenv("VOICES_DIR", str(tmp_path))
    r = client.get("/api/v1/voices")
    assert r.status_code == 200
    byid = {v["id"]: v for v in r.json()}
    assert set(byid) == {"en_US-amy-medium", "en_GB-alba-medium"}
    assert byid["en_US-amy-medium"]["default"] is True
    assert byid["en_US-amy-medium"]["gender"] == "female"
    assert byid["en_GB-alba-medium"]["lang"] == "en-GB"
