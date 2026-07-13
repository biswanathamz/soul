#!/usr/bin/env python3
"""current-time skill — returns the current local date and time.

Protocol: see skillpool/README.md.
  input.format (optional) — a strftime string; otherwise a friendly local default.
"""
import json
import sys
from datetime import datetime


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        req = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"current-time: invalid JSON on stdin: {e}", file=sys.stderr)
        return 1

    fmt = (req.get("input") or {}).get("format")
    now = datetime.now().astimezone()
    try:
        text = now.strftime(fmt) if fmt else now.strftime("%A, %d %B %Y, %H:%M:%S %Z")
    except (ValueError, TypeError) as e:
        print(f"current-time: bad format string: {e}", file=sys.stderr)
        return 1

    result = {"ok": True, "output": text, "display": f"time: {text}"}
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
