---
name: verify
description: Build, run, and drive the SOUL app to verify changes end-to-end.
---

# Verifying SOUL changes

## soul-console (React UI, port 7787)

Launch (two processes):

```bash
cd soul-console
node mock/server.mjs &        # mock soul-orchestrator on :7788 (REST + WS per SPEC §5)
npm run dev &                 # Vite on :7787, proxies /api + /ws to :7788
curl -s localhost:7788/actuator/health   # {"status":"UP"}
```

Drive with system Chrome via `playwright-core` (no browser download needed):

```js
import { chromium } from 'playwright-core';
const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
```

Flows worth driving:
- Send "Write a Python function…" → delegates to coder, streams tokens, shiki code block renders.
- "Research…" → researcher scenario with a markdown table.
- Settings drawer: rebind an agent model (PUT round-trip), voice modes.
- Kill/restart the mock mid-session → reconnect banner, offline badge, auto-recovery.

## Gotchas

- **Never `kill $(lsof -ti tcp:7788)`** — it also matches the Vite proxy's client
  connections and kills the dev server. Use `lsof -ti tcp:7788 -sTCP:LISTEN`.
- `pkill -f "mock/server.mjs"` from a shell whose own command line contains the pattern
  kills itself; kill by listening port instead.
- Selectors: composer is `textarea[aria-label="Message SOUL"]`; stream end =
  `.stream-caret` gone; connection badge text online/connecting/offline lives in `header`.
- The mock's scenario router keys off keywords (code/research/write…) — a message
  containing "document" triggers the writer scenario.
