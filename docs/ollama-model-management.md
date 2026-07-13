# SOUL — Ollama Model Management (Docker + Scripts)

Design document for running Ollama as part of the SOUL Docker stack and managing its
models declaratively with a Python script.

| | |
|---|---|
| **Version** | 0.1 (Draft) |
| **Date** | 2026-07-13 |
| **Status** | Proposed |
| **Parent docs** | [SPEC.md](SPEC.md) §2.1, §9 · [docker-compose.yml](../docker-compose.yml) |
| **Home** | `soul-scripts/ollama/` (first real piece of the `soul-scripts` layer) |

---

## 1. Problem

SOUL's agents each bind to a local Ollama model (SPEC §3.2, §9), but today nothing manages
those models:

- Ollama is **not part of the Docker stack** — the spec assumes a host install that doesn't
  exist yet on the dev machine.
- Models are pulled **by hand**, one `ollama pull` at a time; nothing guarantees the models
  the orchestrator's `application.yml` references are actually installed.
- No story for disk cleanup, upgrades (`:latest` drift), or pre-warming models so the first
  chat isn't a 30-second cold load.

**Goal:** `docker compose up` brings up a fully provisioned Ollama; one manifest file declares
which models SOUL needs; one script reconciles reality against that manifest.

---

## 2. Design Overview

```
                 docker-compose.yml
┌──────────────────────────────────────────────────────┐
│                                                      │
│  ┌────────────────┐  pulls models on startup         │
│  │ soul-model-init │──────────────┐                  │
│  │  (one-shot job) │              ▼                  │
│  └────────────────┘   ┌─────────────────────┐        │
│                       │  soul-ollama         │       │
│  ┌────────────────┐   │  (ollama/ollama)     │       │
│  │soul-orchestrator│──▶│  :11434, healthcheck │       │
│  └────────────────┘   │  volume: model store │       │
│                       └─────────────────────┘        │
└──────────────────────────────────────────────────────┘
                               ▲
        soul-scripts/ollama/   │ same REST API, host or container
┌──────────────────────────────┴───────────────────────┐
│  models.yaml   ── declarative manifest (what SHOULD   │
│                   be installed, per agent role)       │
│  manage.py     ── reconciler CLI: status / sync /     │
│                   pull / rm / prune / warm / verify   │
└──────────────────────────────────────────────────────┘
```

Two pieces, one source of truth:

1. **`models.yaml`** — the manifest. Declares every model SOUL needs, which agent role needs
   it, and how it should be kept warm. The orchestrator's agent↔model bindings must be a
   subset of this file (checked by `manage.py verify`).
2. **`manage.py`** — a Python CLI that talks to Ollama's REST API (never the `ollama` binary,
   so it works identically against the container, a host install, or a remote box).

---

## 3. Docker Integration

### 3.1 `soul-ollama` service

```yaml
soul-ollama:
  image: docker.io/ollama/ollama:latest
  container_name: soul-ollama
  volumes:
    - soul-ollama-models:/root/.ollama      # named volume — models survive rebuilds
  ports:
    - "127.0.0.1:11434:11434"               # localhost only — API has no auth
  environment:
    OLLAMA_KEEP_ALIVE: "30m"                # keep recently used models in RAM
    OLLAMA_MAX_LOADED_MODELS: "2"           # matches SPEC §9 max-concurrent-generations
  healthcheck:
    test: ["CMD", "ollama", "list"]
    interval: 15s
    timeout: 5s
    retries: 10
    start_period: 20s
  restart: unless-stopped
```

- **GPU (optional, NVIDIA):** enabled via a compose profile (`--profile gpu`) that adds
  `deploy.resources.reservations.devices` with `driver: nvidia`. CPU-only remains the default
  so the stack works on any machine.
- The named volume is the important part: model blobs are 4–10 GB each; they must never live
  in a container layer.

### 3.2 `soul-model-init` one-shot job

A tiny service built from `soul-scripts/` that runs `manage.py sync` against `soul-ollama`
and exits. `soul-orchestrator` gains
`depends_on: { soul-model-init: { condition: service_completed_successfully } }`, so the
backend never starts against a half-provisioned Ollama.

First `sync` on a fresh volume downloads tens of GB — the job streams pull progress to its
logs (`docker compose logs -f soul-model-init`) and must tolerate being restarted midway
(Ollama resumes partial pulls server-side).

### 3.3 Networking

- In-stack consumers use `http://soul-ollama:11434`.
- Host consumers (dev scripts, local orchestrator run) use `http://localhost:11434`.
- `manage.py` takes `--host` / `OLLAMA_HOST` env, defaulting to `http://localhost:11434`.

---

## 4. The Manifest — `soul-scripts/ollama/models.yaml`

```yaml
# Single source of truth for which models SOUL needs.
# manage.py sync makes Ollama match this file; manage.py verify checks
# the orchestrator's bindings stay a subset of it.

defaults:
  keep_alive: 30m

models:
  - name: llama3.1:8b
    roles: [super, researcher, sysops]     # who binds to it (SPEC §3.2)
    required: true
    warm: true                             # preload at sync time
  - name: qwen2.5-coder:7b
    roles: [coder]
    required: true
    warm: true
  - name: gemma2:9b
    roles: [writer]
    required: true
  - name: deepseek-r1:8b
    roles: [analyst]
    required: true
  - name: mistral:7b
    roles: []                              # optional spare for experiments
    required: false
```

Rules:

- **Pinned tags only** (`llama3.1:8b`, not `llama3.1`) — `:latest` drift is how two machines
  end up with different behavior.
- `roles` ties the manifest to SPEC §3.2; a model with no roles is allowed but flagged by
  `status` as unused.
- `prune` only ever considers models **not** in this file — the manifest is a whitelist.

---

## 5. The Script — `soul-scripts/ollama/manage.py`

Python 3.11+, **stdlib only** (`urllib`, `json`, `argparse`) plus PyYAML — no venv juggling
for a management tool. Exit code 0/1 so it composes with `&&`, CI, and the init job.

### 5.1 Commands

| Command | What it does | Ollama API used |
|---|---|---|
| `status` | Table: manifest model → installed? size, digest, roles, loaded-in-RAM? | `GET /api/tags`, `GET /api/ps` |
| `sync` | Pull everything `required` that's missing; warm the `warm: true` ones. Idempotent — safe on every stack boot | `POST /api/pull` (streamed progress) |
| `pull <model>` | Pull one model (must be in the manifest, or `--force`) | `POST /api/pull` |
| `rm <model>` | Remove one model (refuses `required` ones without `--force`) | `DELETE /api/delete` |
| `prune` | Remove every installed model **not** in the manifest (with confirmation / `--yes`) | `GET /api/tags`, `DELETE /api/delete` |
| `warm [model…]` | Load models into RAM with an empty generation so first chat is instant | `POST /api/generate` (`"prompt": ""`, `keep_alive`) |
| `verify` | CI-friendly check: manifest parses, tags are pinned, orchestrator bindings ⊆ manifest, all `required` installed | `GET /api/tags` |

### 5.2 Behavior details

- `sync` prints live progress (`pulling llama3.1:8b … 42% 1.9/4.7 GB`) parsed from the
  streamed pull response; non-TTY output (compose logs) falls back to one line per 10%.
- Every command takes `--host`, `--manifest`, `--json` (machine-readable output for the
  orchestrator/CI to consume later).
- `verify` reads the orchestrator's binding config once it exists
  (`soul-orchestrator/src/main/resources/application.yml`); until then it verifies the
  manifest alone and warns that bindings are unchecked.
- Disk guard: before pulling, compare the registry-reported size against free space on the
  volume; abort with a clear message instead of filling the disk at 97% of a 5 GB pull.

### 5.3 Layout

```
soul-scripts/
└── ollama/
    ├── models.yaml        # manifest (section 4)
    ├── manage.py          # CLI (this section)
    ├── Dockerfile         # tiny python:3.12-alpine image for soul-model-init
    └── README.md          # usage cheatsheet
```

---

## 6. Sizing & Expectations

| Model | Disk | RAM to run (Q4) |
|---|---|---|
| llama3.1:8b | ~4.7 GB | ~6 GB |
| qwen2.5-coder:7b | ~4.7 GB | ~6 GB |
| gemma2:9b | ~5.4 GB | ~7 GB |
| deepseek-r1:8b | ~4.9 GB | ~6 GB |
| **Total (required set)** | **~20 GB disk** | 2 loaded ≈ 12–13 GB RAM |

With `OLLAMA_MAX_LOADED_MODELS=2`, the practical minimum is **16 GB RAM** (SPEC §11 already
says this); comfortable is 32 GB. `warm: true` should therefore only mark the 1–2 models the
Super Agent path touches on every request.

---

## 7. Failure Modes

| Failure | Behavior |
|---|---|
| Registry unreachable during `sync` | Retry each pull 3× with backoff; exit 1 listing what's missing. Init job fails → orchestrator doesn't start against missing models |
| Disk full mid-pull | Pre-pull disk guard (§5.2); if it still happens, surface Ollama's error verbatim and exit 1 |
| Model deleted while orchestrator running | Orchestrator's own error path (SPEC §3.3 retry/report). `manage.py status` shows the drift; `sync` repairs it |
| Partial pull interrupted (Ctrl-C, restart) | Ollama resumes layer downloads on the next `pull`/`sync` — document, nothing to build |

---

## 8. Security

- Ollama's API is **unauthenticated** — the container publishes on `127.0.0.1` only, never
  `0.0.0.0`. In-stack traffic stays on the compose network.
- `manage.py` refuses `--host` targets that aren't localhost or the compose hostname unless
  `--i-know-what-im-doing` is passed (guards against pointing `prune` at someone else's box).
- No model auto-updates: `sync` never re-pulls an installed pinned tag; upgrades are an
  explicit manifest edit + PR.

---

## 9. Implementation Plan

| Phase | Deliverable |
|---|---|
| **1** | `soul-scripts/ollama/`: `models.yaml`, `manage.py` with `status` / `pull` / `sync` / `verify`, README |
| **2** | Compose: `soul-ollama` service + volume + healthcheck; GPU profile |
| **3** | `soul-model-init` one-shot job + orchestrator `depends_on` wiring |
| **4** | `warm`, `rm`, `prune`, disk guard, `--json` output |
| **5** | CI: `manage.py verify` as a check on PRs touching the manifest or bindings |

Phases 1–2 are independently useful (host-run script against the containerized Ollama);
3–5 land as the orchestrator work starts.

---

## 10. Open Questions

1. ~~**GPU**: does the dev machine have one?~~ **Answered (2026-07-13):** NVIDIA RTX 2050
   (mobile, ~4 GB VRAM) is present, but the driver is currently broken (no kernel module
   built for kernel 6.17; Secure Boot MOK signing also required). Plan: keep CPU-only as
   default, wire the `gpu` compose profile anyway. With 4 GB VRAM, Ollama will do *partial*
   layer offload on 7–8B Q4 models — a speedup, not full-GPU inference. Prereqs when fixing:
   `nvidia-driver-595-open` + reboot/MOK enroll + `nvidia-container-toolkit` for Docker.
2. ~~**Model set**: 7–9B vs 14B+?~~ **Answered (2026-07-13):** the dev machine has 15 GiB
   RAM — the 7–9B roster in §4 stands, and in practice expect **one** loaded model at a
   time; `OLLAMA_MAX_LOADED_MODELS=2` is the ceiling, not the norm.
3. **Registry mirror/cache**: worth adding a pull-through cache for offline resilience, or
   is the standard registry acceptable for a single-user setup?
