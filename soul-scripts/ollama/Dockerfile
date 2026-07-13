# soul-model-init — one-shot provisioning job.
# Runs `manage.py sync` against soul-ollama, then exits. See docker-compose.yml.
# Build context is soul-scripts/ollama/.
FROM docker.io/library/python:3.12-alpine

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY manage.py models.yaml ./

# In-stack default; overridden by compose. Host runs pass --host localhost:11434.
ENV OLLAMA_HOST=http://soul-ollama:11434

ENTRYPOINT ["python", "manage.py"]
CMD ["sync"]
