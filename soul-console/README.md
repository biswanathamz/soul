# soul-console

React UI microservice for [SOUL](../docs/SPEC.md) — chat + voice interface on port **7787**.
Design: [docs/TDD-soul-console.md](../docs/TDD-soul-console.md).

## Run (dev)

```bash
npm install
npm run dev:mock   # terminal 1 — mock soul-orchestrator on :7788
npm run dev        # terminal 2 — UI on http://localhost:7787
```

The Vite dev server proxies `/api` and `/ws` to `:7788`, so the same UI works unchanged
against the real Spring Boot orchestrator once it exists — just stop the mock.

## Scripts

| Command | What |
| --- | --- |
| `npm run dev` | Vite dev server on :7787 |
| `npm run dev:mock` | Mock backend (REST + WS per SPEC §5) on :7788 |
| `npm test` | Vitest unit/component tests |
| `npm run build` | Typecheck + production build to `dist/` |
| `npm run preview` | Serve the production build on :7787 |
| `npm run lint` | ESLint |

## Run (Docker)

From the repo root:

```bash
docker compose up --build    # UI on http://localhost:7787, mock orchestrator on :7788
```

The UI image is a two-stage build (Node build → nginx). nginx serves the SPA on 7787 and
proxies `/api`, `/actuator`, and `/ws` (with WebSocket upgrade) to `ORCHESTRATOR_URL` —
so the container also works unchanged against the real Spring Boot orchestrator later.

## Voice

v1 uses the browser's Web Speech API: push-to-talk by default, hands-free mode in Settings.
Speech recognition needs Chrome/Edge; the app is fully usable as text-only chat everywhere else.
