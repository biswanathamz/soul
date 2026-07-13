#!/usr/bin/env python3
"""audit-log hook — record every skill call/result to an audit sink.

Observe-only: it never blocks. Writes one JSON line per event to $SOUL_AUDIT_LOG,
or to stderr when that is unset (so it is useful standalone). Protocol: see
hookspool/README.md.
"""
import json
import os
import sys
from datetime import datetime


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        req = json.loads(raw)
    except json.JSONDecodeError as e:
        # Observe-only: a bad frame must not hold up the turn.
        print(f"audit-log: invalid JSON on stdin: {e}", file=sys.stderr)
        return 0

    context = req.get("context") or {}
    record = {
        "ts": datetime.now().astimezone().isoformat(),
        "event": req.get("event"),
        "agent": context.get("agent"),
        "conversationId": context.get("conversationId"),
        "payload": req.get("payload"),
    }
    line = json.dumps(record)

    sink = os.environ.get("SOUL_AUDIT_LOG")
    try:
        if sink:
            with open(sink, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        else:
            print(line, file=sys.stderr)
    except OSError as e:
        print(f"audit-log: cannot write sink: {e}", file=sys.stderr)

    # No stdout action → default allow.
    return 0


if __name__ == "__main__":
    sys.exit(main())
