#!/usr/bin/env python3
"""echo skill — returns the text it is given.

Reference implementation of the SOUL skill JSON protocol (see skillpool/README.md):
  stdin : {"skill": "echo", "input": {"text": "..."}, "context": {...}}
  stdout: {"ok": true, "output": "...", "display": "..."}
  exit 0 on success; non-zero + stderr on failure.
"""
import json
import sys


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        req = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"echo: invalid JSON on stdin: {e}", file=sys.stderr)
        return 1

    text = (req.get("input") or {}).get("text", "")
    result = {"ok": True, "output": text, "display": f"echoed {len(text)} char(s)"}
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
