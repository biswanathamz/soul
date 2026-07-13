# hookspool

Repo-root pool of **hooks** — event-triggered behaviors the *system* runs (the model neither
chooses nor sees them). Hooks are how SOUL logs, enforces safety, and automates around every
step. Design: [docs/manager-agent.md](../docs/manager-agent.md) §4.

A hook is a directory:

```
hookspool/<name>/
├── hook.yaml      # manifest (below)
└── run.py         # entrypoint (any executable; dispatched by shebang)
```

A hook can **observe** (log/telemetry), **modify** (rewrite a message / inject context / redact
a result), or **block** (veto a skill call or response).

## Manifest — `hook.yaml`

| Field | Meaning |
| --- | --- |
| `name` | Unique hook name (matches the directory). |
| `description` | What it does. |
| `event` | One event, or a list of events, this hook handles (table below). |
| `entrypoint` | Executable to run, relative to the hook dir. |
| `matcher` | *(optional)* regex over skill-name / content to narrow when it runs. |
| `blocking` | `true` = allowed to veto. A non-blocking hook's block is logged, not enforced. |
| `always-apply` | *(optional)* `true` = runs for **every** agent, un-skippable. Safety gates only. |
| `timeout_seconds` | Hard cap on execution. |

### Events

| Event | Fires | A blocking hook can… |
| --- | --- | --- |
| `session_start` | new conversation | — (setup) |
| `user_message_received` | each user turn, pre-model | reject / rewrite the message |
| `before_model` | before each model call | inject system context |
| `before_skill` | before a script skill runs | block / rewrite the skill input |
| `after_skill` | after a skill returns | redact / annotate the result |
| `before_respond` | before the final reply streams | block / edit the reply |
| `session_end` | conversation closed | — (teardown) |
| `on_error` | any step errors | observe (alerting) |

## Protocol (JSON in / JSON out)

```jsonc
// stdin
{ "event": "before_skill",
  "payload": { "skill": "web-fetch", "input": { "url": "…" } },
  "context": { "conversationId": "…", "agent": "super" } }
// stdout (optional)
{ "action": "allow" | "modify" | "block", "reason": "…", "patch": { … } }
```

- **`allow`** (default) — proceed. Emit nothing, or `{"action":"allow"}`. Exit 0.
- **`modify`** — proceed with `patch` applied. Any hook may modify. Per-event patch shapes:
  - `before_model` → `{"append_system": "text"}` (appended to system context)
  - `user_message_received` → `{"message": "rewritten text"}`
  - `after_skill` → `{"output": "redacted/annotated result"}`
- **`block`** — veto. Honored only when `blocking: true`; the `reason` (stdout or stderr) is
  surfaced. From a non-blocking hook a block is downgraded to a logged warning.
- **Exit code**: `0` = allow. A **blocking** hook exiting **non-zero** = block, with stderr as
  the reason — a shorthand for the stdout `block` action. Non-blocking exit codes are logged only.

Ordering: hooks on the same event run in directory-name order; the first `block` short-circuits.

Run one by hand:

```bash
echo '{"event":"before_skill","payload":{"skill":"echo","input":{"text":"hi"}}}' | hookspool/block-secrets/run.py; echo "exit $?"
```

## Adding a hook

1. Create `hookspool/<name>/` with `hook.yaml` and `run.py` (+ shebang, `chmod +x`).
2. Add `<name>` to an agent's `hooks:` list to grant it — unless it's an `always-apply` safety
   gate, which applies everywhere automatically.
3. `python3 soul-scripts/pooltest.py` (or `make pools-verify`) to validate + smoke-test.
