# SOUL

> 🚧 **Work in progress**

**SOUL** — *Supervised Orchestration of Unified LLM-agents* — is a locally hosted, JARVIS-inspired multi-agent AI assistant.

## The Idea

You talk to a single **Super Agent** (the "Manager") through a modern web UI — by **chat or voice**. The Super Agent doesn't do the specialist work itself: it understands your intent, breaks it into tasks, and delegates each one to a fleet of **sub-agents** (coder, researcher, writer, analyst, sysops), each powered by the local **Ollama** model best suited for the job. Results flow back up and are synthesized into one coherent answer.

Everything runs locally — no cloud APIs, no data leaving your machine.

- **UI**: `soul-console` — React, black & yellow theme, port `7787`
- **Backend**: `soul-orchestrator` — Spring Boot, agent orchestration, port `7788`
- **Tools**: `soul-scripts` — Python scripts agents use to act on the world

📄 Full design: [docs/SPEC.md](docs/SPEC.md)
