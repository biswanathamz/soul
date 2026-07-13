# SOUL

> 🚧 **Work in progress**

**SOUL** — *Supervised Orchestration of Unified LLM-agents* — is a locally hosted, JARVIS-inspired multi-agent AI assistant.

## The Idea

You talk to a single **Super Agent** (the "Manager") through a modern web UI — by **chat or voice**. The Super Agent doesn't do the specialist work itself: it understands your intent, breaks it into tasks, and delegates each one to a fleet of **sub-agents** (coder, researcher, writer, analyst, sysops), each powered by the local **Ollama** model best suited for the job. Results flow back up and are synthesized into one coherent answer.

Everything runs locally — no cloud APIs, no data leaving your machine.

## Components

| Service | What | Port |
| --- | --- | --- |
| `soul-console` | React UI (chat + voice, black & yellow theme) | `7787` |
| `soul-orchestrator` | The Manager agent — Spring Boot service, drives the agent loop | `7788` |
| `soul-scripts/ollama` | Declarative Ollama model management (manifest + `manage.py`) | — |
| `soul-ollama` | Local model runtime (Ollama) — the single source of models | `11434` |

The UI talks to the real Spring Boot orchestrator, which runs the Manager agent against local **Ollama** models. Ollama is the one and only model provider — there is no mock backend.

## Prerequisites

- **Podman** + **podman-compose**, *or* **Docker** with the Compose plugin — to run the containers.
- **GNU Make** — the lifecycle is driven entirely through `make`.
- *(Optional)* **Node 20+** — only for running the UI in host dev mode.
- *(Optional)* **Python 3.11+ and PyYAML** — only for the model-management tooling (`make models-deps` installs PyYAML).

Nothing else needs installing globally — the container images bring their own toolchains.

## Quick start

```bash
make models-sync   # one-time: pull the Manager's model into Ollama (a few GB)
make up            # build + start Ollama, the orchestrator, and the UI
```

Then open **http://localhost:7787**. `make up` builds the images (first run: a few minutes) and starts Ollama, the Spring Boot orchestrator, and the UI as containers in the background. `make models-sync` only needs re-running when the model list changes.

Stop everything with:

```bash
make down
```

## Running with Make

`make help` lists every target. The common ones:

| Command | What it does |
| --- | --- |
| `make up` | Build + start the full stack (→ http://localhost:7787) |
| `make down` | Stop and remove all SOUL containers |
| `make restart` | `down` then `up` |
| `make build` / `make rebuild` | Build images / rebuild with no cache |
| `make ps` | Show running containers |
| `make logs` | Tail the stack logs |

The Makefile uses **podman-compose** by default. To use Docker instead, override the engine on any target:

```bash
make up COMPOSE="docker compose"
```

## Adding local models (Ollama)

This is opt-in and separate from the UI, because the first sync downloads **~20 GB** of models.

```bash
make ollama-up        # start the Ollama runtime on localhost:11434
make models-sync      # pull + warm every model in soul-scripts/ollama/models.yaml
make models-status    # see what's installed / loaded
```

Which models SOUL uses is declared in [soul-scripts/ollama/models.yaml](soul-scripts/ollama/models.yaml) and reconciled by `manage.py` — see [soul-scripts/ollama/README.md](soul-scripts/ollama/README.md). Other model targets: `make models-verify`, `make models-warm`, `make models-prune`.

**GPU:** `make ollama-gpu` starts Ollama with NVIDIA acceleration, but it requires **Docker** (not podman), the NVIDIA Container Toolkit, and a working driver. CPU is the default and works everywhere.

## Host dev mode (no containers)

For fast UI iteration with hot reload, run the orchestrator and the console directly on the host. You need Ollama running (`make ollama-up`) with the model pulled (`make models-sync`). Use two terminals:

```bash
make install                                   # one-time: npm install
cd soul-orchestrator && ./gradlew bootRun      # terminal 1: the Manager on :7788
make dev                                        # terminal 2: Vite dev server on :7787
```

The Vite dev server proxies `/api`, `/actuator`, and `/ws` to the orchestrator on `:7788`. Other dev targets: `make test` (unit/component tests), `make console-build` (typecheck + production build), `make orchestrator-test` (JUnit), `make verify` (all checks).

## Cleanup

```bash
make clean          # stop containers, remove the network (keeps downloaded models)
make clean-models   # delete the model volume (frees ~20 GB)
```

## Notes

- **Use `make`, not `podman compose` directly.** podman-compose ignores compose `profiles:`, so a bare `podman-compose up` would also start Ollama's big pull. The Makefile always names services explicitly to avoid that — and it uses `podman-compose` (hyphenated), since `podman compose` (spaced) routes to a broken provider on some setups.
- Ollama's API is unauthenticated, so its port is published on `127.0.0.1` only.

## Docs

- 📄 System design: [docs/SPEC.md](docs/SPEC.md)
- 🎨 UI design: [docs/TDD-soul-console.md](docs/TDD-soul-console.md)
