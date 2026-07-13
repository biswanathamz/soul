# soul-console

React UI microservice for [SOUL](../docs/SPEC.md) — chat + voice interface on port **7787**.
Design: [docs/TDD-soul-console.md](../docs/TDD-soul-console.md).

## Run (dev)

Start the orchestrator first (`make up` from the repo root, or run the jar), then:

```bash
npm install
npm run dev        # UI on http://localhost:7787
```

The Vite dev server proxies `/api`, `/actuator`, and `/ws` to the orchestrator on `:7788`
(override with `VITE_API_TARGET`).

## Scripts

| Command | What |
| --- | --- |
| `npm run dev` | Vite dev server on :7787 (proxies to the orchestrator on :7788) |
| `npm test` | Vitest unit/component tests |
| `npm run build` | Typecheck + production build to `dist/` |
| `npm run preview` | Serve the production build on :7787 |
| `npm run lint` | ESLint |

## Run (Docker)

From the repo root:

```bash
make up    # orchestrator + UI containers on http://localhost:7787 (needs host Ollama — see repo README)
```

The UI image is a two-stage build (Node build → nginx). nginx serves the SPA on 7787 and
proxies `/api`, `/actuator`, and `/ws` (with WebSocket upgrade) to `ORCHESTRATOR_URL`,
the real Spring Boot orchestrator.

## Voice

v1 uses the browser's Web Speech API: push-to-talk by default, hands-free mode in Settings.
Speech recognition needs Chrome/Edge; the app is fully usable as text-only chat everywhere else.
