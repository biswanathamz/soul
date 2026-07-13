# skillpool

Repo-root pool of **skills** — reusable, agent-agnostic capabilities. Any agent can use any
skill; each agent's config just names the ones it's given. Design:
[docs/manager-agent.md](../docs/manager-agent.md) §3.

A skill is a directory. Two kinds:

- **script skill** — runs an executable and returns a result the model can use.
- **prompt skill** — injects text into the model's context; no code runs.

```
skillpool/<name>/
├── skill.yaml     # manifest (below)
├── run.py         # entrypoint — script skills (any executable; dispatched by shebang)
└── prompt.md      # injected text — prompt skills
```

## Manifest — `skill.yaml`

| Field | Applies to | Meaning |
| --- | --- | --- |
| `name` | all | Unique skill name (matches the directory). |
| `description` | all | What it does / when to use it. **This is what the model sees** to decide. |
| `version` | all | Manifest version. |
| `type` | all | `script` or `prompt`. |
| `entrypoint` | script | Executable to run, relative to the skill dir. |
| `parameters` | script | JSON-Schema for the input — becomes the model-facing tool schema. |
| `timeout_seconds` | script | Hard cap on execution. |
| `permissions` | all | Least-privilege declaration: `network: bool`, `filesystem: none\|workspace\|readonly-workspace`. Enforced by the runtime (phase 5). |
| `prompt` | prompt | File with the injected text (default `prompt.md`). |
| `always` | prompt | `true` = inject every turn; otherwise injected on description match. |
| `example.input` | script | Sample input used by `pooltest.py` to smoke-run the skill. |

## Script-skill protocol (JSON in / JSON out)

The runner feeds the entrypoint JSON on **stdin** and reads JSON from **stdout**:

```jsonc
// stdin
{ "skill": "current-time", "input": { "format": "%H:%M" }, "context": { "conversationId": "…", "agent": "super" } }
// stdout
{ "ok": true, "output": "14:07", "display": "time: 14:07" }
```

- `output` is returned to the model as the tool result. `display` (optional) is what the UI
  activity view shows.
- **exit 0** = success. **Non-zero** = failure; stderr becomes the error the model sees.
- Language-neutral: the entrypoint is run by its shebang, so any executable works — the shipped
  examples are Python.

Run one by hand:

```bash
echo '{"skill":"echo","input":{"text":"hi"}}' | skillpool/echo/run.py
```

## Adding a skill

1. Create `skillpool/<name>/` with a `skill.yaml` and either `run.py` (+ shebang, `chmod +x`)
   or `prompt.md`.
2. Add `<name>` to an agent's `skills:` list in the orchestrator config to grant it.
3. `python3 soul-scripts/pooltest.py` (or `make pools-verify`) to validate + smoke-test.
