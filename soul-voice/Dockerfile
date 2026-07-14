# soul-voice — local neural TTS (Piper) on :7789.
# Voice models (~60 MB each) are baked in at build time from HuggingFace.
#   docker build soul-voice/          (or: make build — it's part of the stack)
FROM docker.io/library/python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Voice ids follow Piper's <locale>-<name>-<quality> convention; the HF path is
# derived from the id. Default: Amy (US, natural female) + Alba (GB) to pick by ear.
ARG VOICES="en_US-amy-medium en_GB-alba-medium"
RUN mkdir -p voices && cd voices && \
    for v in $VOICES; do \
      locale=$(echo "$v" | cut -d- -f1); \
      name=$(echo "$v" | cut -d- -f2); \
      quality=$(echo "$v" | cut -d- -f3); \
      family=$(echo "$locale" | cut -d_ -f1); \
      base="https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/$family/$locale/$name/$quality"; \
      echo "fetching $v …"; \
      curl -fsSL -o "$v.onnx" "$base/$v.onnx" && \
      curl -fsSL -o "$v.onnx.json" "$base/$v.onnx.json" || exit 1; \
    done

COPY app.py .

ENV VOICES_DIR=/app/voices \
    DEFAULT_VOICE=en_US-amy-medium

EXPOSE 7789
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=10s \
  CMD curl -fsS http://127.0.0.1:7789/health || exit 1
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7789"]
