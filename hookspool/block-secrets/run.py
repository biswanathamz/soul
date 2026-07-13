#!/usr/bin/env python3
"""block-secrets hook — veto skill calls whose arguments contain obvious credentials.

Blocking safety gate on `before_skill`. Scans the skill's input for common secret
shapes; on a match it blocks (exit 2 + reason on stderr) without echoing the secret.
Protocol: see hookspool/README.md.
"""
import json
import re
import sys

PATTERNS = [
    ("AWS access key id", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("private key block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"ghp_[A-Za-z0-9]{36}")),
    ("Slack token", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("bearer token", re.compile(r"[Bb]earer\s+[A-Za-z0-9._\-]{20,}")),
    (
        "credential assignment",
        re.compile(r"(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|token)\s*[=:]\s*\S{6,}"),
    ),
]


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        req = json.loads(raw)
    except json.JSONDecodeError as e:
        # Fail closed: a safety gate that can't parse its input blocks.
        print(f"block-secrets: invalid JSON on stdin: {e}", file=sys.stderr)
        return 2

    payload = req.get("payload") or {}
    haystack = json.dumps(payload.get("input", payload))

    for label, rx in PATTERNS:
        if rx.search(haystack):
            reason = f"skill arguments appear to contain a {label}"
            # Block via both channels: structured stdout + non-zero exit. Never echo the secret.
            print(json.dumps({"action": "block", "reason": reason}))
            print(f"blocked: {reason}", file=sys.stderr)
            return 2

    return 0  # allow


if __name__ == "__main__":
    sys.exit(main())
