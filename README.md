# SOUL

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![verify-orchestrator](https://github.com/biswanathamz/soul/actions/workflows/verify-orchestrator.yml/badge.svg)](https://github.com/biswanathamz/soul/actions/workflows/verify-orchestrator.yml)
[![verify-pools](https://github.com/biswanathamz/soul/actions/workflows/verify-pools.yml/badge.svg)](https://github.com/biswanathamz/soul/actions/workflows/verify-pools.yml)
[![verify-models](https://github.com/biswanathamz/soul/actions/workflows/verify-models.yml/badge.svg)](https://github.com/biswanathamz/soul/actions/workflows/verify-models.yml)

> 🚧 **Work in progress**

**SOUL** — *Supervised Orchestration of Unified LLM-agents* — is a locally hosted, JARVIS-inspired multi-agent AI assistant.

## The Idea

You talk to a single **Super Agent** (the "Manager") through a modern web UI — by **chat or voice**. The Super Agent doesn't do the specialist work itself: it understands your intent, breaks it into tasks, and delegates each one to a fleet of **sub-agents** (coder, researcher, writer, analyst, sysops), each powered by the local **Ollama** model best suited for the job. Results flow back up and are synthesized into one coherent answer.

Everything runs locally — no cloud APIs, no data leaving your machine.

## Components

| Service | What | Runs as | Port |
| --- | --- | --- | --- |
| `soul-console` | React UI (chat + voice, black & yellow theme) | container | `7787` |
| `soul-orchestrator` | The Manager agent — Spring Boot service, drives the agent loop | container | `7788` |
| `soul-scripts/ollama` | Declarative Ollama model management (manifest + `manage.py`) | host script | — |
| Ollama | Local model runtime — the single source of models | **host-native (GPU)** | `11434` |

The UI talks to the real Spring Boot orchestrator, which runs the Manager agent against local **Ollama** models. Ollama is the one and only model provider — there is no mock backend.

**Topology:** only the orchestrator and the console run in containers. **Ollama runs natively on the host** so it can use the NVIDIA GPU directly (rootless containers can't reach the GPU here without extra toolkit setup). The orchestrator container reaches the host's Ollama via `host.containers.internal:11434`, so Ollama must listen on `0.0.0.0:11434` (not just localhost).

## Prerequisites

- **Podman** + **podman-compose**, *or* **Docker** with the Compose plugin — to run the orchestrator + console containers.
- **GNU Make** — the lifecycle is driven through `make`.
- **`sudo` + `curl`** — `make setup` installs host Ollama via the official script (needs root once).
- For GPU acceleration: an **NVIDIA GPU with a working driver** (CPU works too, just slower).
- *(Optional)* **Node 20+** — only for running the UI in host dev mode.

`make setup` installs the rest — host Ollama (the model runtime) and Python/PyYAML (the model tooling). Only the orchestrator and console images bring their own toolchains.

## Quick start

```bash
make setup   # one-time: install host Ollama (GPU) + Python deps + pull the model
make up      # build + start the orchestrator + console containers
```

Then open **http://localhost:7787**.

- **`make setup`** (once) installs Ollama on the host and runs it as a **systemd GPU service** on `0.0.0.0:11434`, installs PyYAML, and pulls the Manager's model (a few GB). It uses `sudo` for the install. Re-run `make models-sync` alone whenever the model list changes.
- **`make up`** builds the two images (first run: a few minutes) and starts the orchestrator and UI as containers; they reach the host's Ollama over `host.containers.internal`.

Stop the containers with:

```bash
make down
```

(Host Ollama keeps running as a service — stop it with `sudo systemctl stop ollama`.)

## Running with Make

`make help` lists every target. The common ones:

| Command | What it does |
| --- | --- |
| `make up` | Build + start the orchestrator + console containers (→ http://localhost:7787) |
| `make down` | Stop and remove the SOUL containers (leaves host Ollama running) |
| `make restart` | `down` then `up` |
| `make build` / `make rebuild` | Build images / rebuild with no cache |
| `make ps` | Show running containers |
| `make logs` | Tail the stack logs |

The Makefile uses **podman-compose** by default. To use Docker instead, override the engine on any target:

```bash
make up COMPOSE="docker compose"
```

## Ollama + local models

Ollama runs on the host so it can use the GPU. `make setup` installs it and starts it as a systemd service; after that, manage the model set with:

```bash
make models-sync      # pull + warm every model in soul-scripts/ollama/models.yaml
make models-status    # see what's installed / loaded
```

The model tooling (`manage.py`) talks to Ollama's REST API directly on the host — no container. Which models SOUL uses is declared in [soul-scripts/ollama/models.yaml](soul-scripts/ollama/models.yaml) and reconciled by `manage.py` — see [soul-scripts/ollama/README.md](soul-scripts/ollama/README.md). Other model targets: `make models-verify`, `make models-warm`, `make models-prune`.

Related Ollama targets: `make ollama-install` (install + configure the service — part of `make setup`), and `make ollama-serve` (run it in the foreground instead of the systemd service — stop the service first).

**GPU:** because Ollama is host-native, it uses the NVIDIA GPU automatically when the driver is present — no NVIDIA Container Toolkit or CDI setup needed. On a small GPU (e.g. 4 GB) it offloads as many layers as fit; CPU handles the rest.

## Host dev mode (no containers)

For fast UI iteration with hot reload, run the orchestrator and the console directly on the host too. You need Ollama running (the `make setup` service, or `make ollama-serve`) with the model pulled. Use two terminals:

```bash
make install                                   # one-time: npm install
cd soul-orchestrator && ./gradlew bootRun      # terminal 1: the Manager on :7788
make dev                                        # terminal 2: Vite dev server on :7787
```

The Vite dev server proxies `/api`, `/actuator`, and `/ws` to the orchestrator on `:7788`. Other dev targets: `make test` (unit/component tests), `make console-build` (typecheck + production build), `make orchestrator-test` (JUnit), `make verify` (all checks).

## Cleanup

```bash
make clean          # stop the containers, remove the network (host Ollama + models untouched)
make models-prune   # remove installed models NOT in the manifest
```

Models live in the host's Ollama store (`~/.ollama`); remove one with `ollama rm <model>`.

## Notes

- **Use `make`, not `podman compose` directly.** It uses `podman-compose` (hyphenated), since `podman compose` (spaced) routes to a broken provider on some setups.
- **Ollama must listen on `0.0.0.0`** for the orchestrator container to reach it via `host.containers.internal` — `make ollama-serve` sets that. Its API is unauthenticated, so keep the box off untrusted networks or firewall port 11434.

## Docs

- 🗺️ Architecture map: [ARCHITECTURE.md](ARCHITECTURE.md)
- 📄 System design: [docs/SPEC.md](docs/SPEC.md)
- 🎨 UI design: [docs/TDD-soul-console.md](docs/TDD-soul-console.md)

## Contributing

Contributions are welcome — bug reports, docs, new **skills/hooks** (the easiest
entry point), and code. Start with [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). All changes land via PR (`master` is
protected); `make verify` runs the full check suite locally.

## License

SOUL is open source under the [MIT License](LICENSE).
