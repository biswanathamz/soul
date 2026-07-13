#!/usr/bin/env python3
"""inject-time hook — add the current timestamp to model context each turn.

Runs on `before_model` and returns a `modify` action whose patch appends a line to
the system context, so the model always knows "now". Protocol: see hookspool/README.md.
"""
import json
import sys
from datetime import datetime


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        json.loads(raw)  # validate the frame; content isn't needed
    except json.JSONDecodeError as e:
        # Non-blocking: don't hold up the turn on a bad frame.
        print(f"inject-time: invalid JSON on stdin: {e}", file=sys.stderr)
        return 0

    now = datetime.now().astimezone().strftime("%A, %d %B %Y, %H:%M %Z")
    patch = {"append_system": f"The current date and time is {now}."}
    print(json.dumps({"action": "modify", "patch": patch}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
