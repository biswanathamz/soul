# Security Policy

SOUL is a locally hosted assistant — it is designed to run on your own machine
with no cloud services on the answer path. Still, security issues matter.

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Instead, use
GitHub's private vulnerability reporting on this repository
(Security → Report a vulnerability). You'll get a response as soon as possible.

## Scope worth knowing about

- **Ollama's API is unauthenticated.** SOUL binds it to `0.0.0.0:11434` so the
  orchestrator container can reach it — keep the machine off untrusted networks
  or firewall the port (see README notes).
- The `block-secrets` hook (`hookspool/`) is a best-effort safety gate against
  credentials passing through skill calls — improvements welcome.
- The web UI and services are intended for `localhost` use, not public exposure.
