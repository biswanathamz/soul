# soul-scripts / ollama

Declarative Ollama model management for SOUL. One manifest ([models.yaml](models.yaml))
declares which models SOUL needs; one script ([manage.py](manage.py)) reconciles a running
Ollama against it. Design: [docs/ollama-model-management.md](../../docs/ollama-model-management.md).

`manage.py` talks only to Ollama's REST API, so the same commands work against the compose
container, a host install, or a remote box.

## Requirements

Python 3.11+ and PyYAML (`pip install -r requirements.txt`). Nothing else — the rest is stdlib.

## Commands

```bash
python manage.py status          # manifest vs what's installed / loaded
python manage.py sync            # pull missing required models, warm the warm ones (idempotent)
python manage.py pull <model>    # pull one model (must be in the manifest, or --force)
python manage.py rm <model>      # remove one (refuses required models without --force)
python manage.py prune           # remove installed models NOT in the manifest (--yes to skip prompt)
python manage.py warm [model...] # load models into RAM (default: all warm:true)
python manage.py verify          # CI check: pinned tags, bindings ⊆ manifest, required installed
```

Global flags: `--host` (or `$OLLAMA_HOST`, default `http://localhost:11434`), `--manifest`,
`--json` (machine-readable), `--min-free-gb` (disk guard, default 10).

## Typical use

```bash
# Against the compose Ollama (see repo-root docker-compose.yml):
docker compose --profile ollama up -d soul-ollama
python manage.py --host http://localhost:11434 sync

# CI / pre-commit sanity check (no Ollama needed — static checks still run):
python manage.py verify
```

## Safety

- **Pinned tags only.** `verify` fails on `:latest` or untagged names.
- **`prune`/`rm` are host-guarded.** They refuse a non-local `--host` unless you pass
  `--i-know-what-im-doing`, so you can't accidentally wipe another machine's models.
- **No auto-upgrades.** `sync` never re-pulls an installed pinned tag; upgrading a model is an
  explicit manifest edit reviewed via PR.
