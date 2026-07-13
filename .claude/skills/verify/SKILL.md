---
name: verify
description: Build, run, and drive the SOUL app to verify changes end-to-end.
---

# Verifying SOUL changes

The stack is UI (soul-console :7787) → real Spring Boot orchestrator (:7788) → Ollama
(:11434, the single model provider). There is no mock backend.

## Fast checks (no model needed)

```bash
make orchestrator-test   # JUnit — drives the full Manager loop with StubOllamaClient
make test                # console vitest
make verify              # everything: models manifest + pools + orchestrator + console
```

`orchestrator-test` uses the `stub-ollama` profile, so it needs neither Ollama nor a
pulled model — use it to verify orchestrator logic quickly.

## End-to-end (real Ollama)

Needs Ollama running with the Manager's model pulled (`make ollama-up && make models-sync`).

Launch orchestrator + UI on the host (two processes):

```bash
cd soul-orchestrator && ./gradlew bootRun &   # real Manager on :7788 (default profile → Ollama)
cd soul-console && npm run dev &              # Vite on :7787, proxies /api + /actuator + /ws
curl -s localhost:7788/actuator/health        # {"status":"UP"}
curl -s localhost:7788/api/v1/models          # should list llama3.1:8b from Ollama
```

Or run the whole thing in containers with `make up` (build + start Ollama + orchestrator + UI).

Drive the UI with system Chrome via `playwright-core` (no browser download needed):

```js
import { chromium } from 'playwright-core';
const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
```

Flows worth driving:
- Send **"What is the current time?"** → Manager calls the `current-time` skill (watch for a
  `tool.call` event), streams tokens, commits a final answer. Real 8B inference on a 4GB GPU
  is slow (~30–40s) — wait for the stream to finish, don't assume a hang.
- Try to get it to echo a credential → the `block-secrets` `always-apply` hook vetoes the
  skill call and surfaces an `error` event ("blocked").
- Settings drawer: rebind the Manager's model (PUT `/api/v1/agents/super/model` round-trip),
  voice modes.
- Kill/restart the orchestrator mid-session → reconnect banner, offline badge, auto-recovery.

## Gotchas

- **Never `kill $(lsof -ti tcp:7788)`** — it also matches the Vite proxy's client
  connections and kills the dev server. Use `lsof -ti tcp:7788 -sTCP:LISTEN`.
- Selectors: composer is `textarea[aria-label="Message SOUL"]`; stream end =
  `.stream-caret` gone; connection badge text online/connecting/offline lives in `header`.
- If `/api/v1/models` is empty or chat errors, Ollama isn't up or the model isn't pulled —
  `make models-status` to check.
