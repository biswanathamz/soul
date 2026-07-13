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
| `soul-orchestrator` | Agent orchestration — **currently a mock**; Spring Boot service later | `7788` |
| `soul-scripts/ollama` | Declarative Ollama model management (manifest + `manage.py`) | — |
| `soul-ollama` | Local model runtime (Ollama), started on demand | `11434` |

Today the stack runs the UI against a **mock orchestrator** (it implements the real API contracts), so you get a fully working chat experience before the Spring Boot backend and Ollama exist.

## Prerequisites

- **Podman** + **podman-compose**, *or* **Docker** with the Compose plugin — to run the containers.
- **GNU Make** — the lifecycle is driven entirely through `make`.
- *(Optional)* **Node 20+** — only for running the UI in host dev mode.
- *(Optional)* **Python 3.11+ and PyYAML** — only for the model-management tooling (`make models-deps` installs PyYAML).

Nothing else needs installing globally — the container images bring their own toolchains.

## Quick start

```bash
make up
```

Then open **http://localhost:7787**. That starts the UI and the mock orchestrator as podman containers in the background. First run builds the images (a minute or two); after that it's a few seconds.

Stop everything with:

```bash
make down
```

That's the whole loop. `make up` does **not** download any models — it's a fast, self-contained UI demo.

## Running with Make

`make help` lists every target. The common ones:

| Command | What it does |
| --- | --- |
| `make up` | Start the UI stack (→ http://localhost:7787) |
| `make down` | Stop and remove all SOUL containers |
| `make restart` | `down` then `up` |
| `make build` / `make rebuild` | Build images / rebuild with no cache |
| `make ps` | Show running containers |
| `make logs` | Tail the UI stack logs |

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

For fast UI iteration with hot reload, run the console and mock backend directly. Use two terminals:

```bash
make install     # one-time: npm install
make mock        # terminal 1: mock orchestrator (REST + WS on :7788)
make dev         # terminal 2: Vite dev server on :7787
```

Other dev targets: `make test` (unit/component tests), `make console-build` (typecheck + production build), `make verify` (manifest checks + tests).

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
