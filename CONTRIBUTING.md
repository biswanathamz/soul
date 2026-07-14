# Contributing to SOUL

Thanks for your interest in SOUL! This project is a locally hosted, JARVIS-inspired
multi-agent assistant — contributions of all sizes are welcome: bug reports, docs,
skills/hooks, and code.

## Getting oriented

Read **[ARCHITECTURE.md](ARCHITECTURE.md)** first — it's the map: components, wire
contracts, design principles, and the request lifecycle. Deep design notes live in
[docs/](docs/).

The short version: `soul-console` (React, :7787) talks to `soul-orchestrator`
(Spring Boot, :7788 — the Manager agent) over REST + WebSocket; the Manager runs
skills/hooks from the root-level pools and calls local **Ollama** models. Everything
runs locally.

## Dev setup

Prerequisites: Podman + podman-compose (or Docker), GNU Make, and for host-mode dev
Node 20+ / Java 17 / Python 3.11+.

```bash
make setup     # one-time: host Ollama (GPU) + Python deps + pull the model
make up        # build + start the app containers → http://localhost:7787
```

Fast iteration (host mode, hot reload):

```bash
make install                                  # npm install for the console
cd soul-orchestrator && ./gradlew bootRun     # terminal 1: the Manager on :7788
make dev                                      # terminal 2: Vite on :7787
```

`make help` lists every target.

## Running the checks

All of CI, locally:

```bash
make verify    # models manifest + skill/hook pools + orchestrator JUnit + console vitest
```

Please run it before opening a PR. New behavior should come with tests — the codebase
keeps pure logic (state machines, parsers, detectors) separated from I/O precisely so
it can be unit-tested; follow that pattern.

## Making changes

1. **Branch** off `master` (`feature/…`, `fix/…`, `docs/…`). Direct pushes to `master`
   are blocked — all changes land via PR.
2. **Match the surrounding code.** Style, naming, and comment density are consistent
   per service; the linters/formatters that exist (eslint for the console) must pass.
3. **Design-doc first for big features.** Substantial features start as a design doc
   in `docs/` (see `docs/manager-agent.md` for the shape), then implementation.
4. **Open a PR** with a clear description of what and why. CI must be green.

## Easy ways to contribute: skills & hooks

The most self-contained contribution is a new **skill** (a tool the Manager can call)
or **hook** (lifecycle behavior). They're language-neutral — a directory with a YAML
manifest + an executable that speaks JSON over stdin/stdout:

- Look at [skillpool/current-time/](skillpool/current-time/) or
  [hookspool/block-secrets/](hookspool/block-secrets/) as templates.
- Validate with `make pools-verify` (no model needed).
- Grant it to an agent in `soul-orchestrator/src/main/resources/application.yml`.

## Reporting bugs / proposing features

Use the issue templates. For bugs, include: what you ran (`make up` vs host mode),
browser + OS, and relevant logs (`make logs`, browser console for voice/UI issues).

## Code of Conduct

Be kind. By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE).
