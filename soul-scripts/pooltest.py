#!/usr/bin/env python3
"""SOUL — skillpool / hookspool validator + smoke-tester.

Validates every skill and hook manifest, then drives each one through its JSON
stdin/stdout protocol (docs/manager-agent.md §3.4, §4.3). No orchestrator or model
needed — this is the standalone check that phase 1 works.

    python3 soul-scripts/pooltest.py            # validate + smoke-test both pools
    python3 soul-scripts/pooltest.py --json     # machine-readable summary
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("PyYAML is required: pip install pyyaml\n")
    sys.exit(2)

REPO = Path(__file__).resolve().parents[1]
SKILLPOOL = REPO / "skillpool"
HOOKSPOOL = REPO / "hookspool"

SKILL_TYPES = {"script", "prompt"}
HOOK_EVENTS = {
    "session_start",
    "user_message_received",
    "before_model",
    "before_skill",
    "after_skill",
    "before_respond",
    "session_end",
    "on_error",
}
HOOK_ACTIONS = {"allow", "modify", "block"}
CTX = {"conversationId": "pooltest", "agent": "pooltest"}


class Failure(Exception):
    pass


def run_entrypoint(path: Path, payload: dict, timeout: float,
                   env: dict | None = None) -> subprocess.CompletedProcess:
    """Execute an entrypoint by its shebang, feeding JSON on stdin."""
    try:
        return subprocess.run(
            [str(path)],
            input=json.dumps(payload),
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=str(path.parent),
            env={**os.environ, **(env or {})},
        )
    except PermissionError:
        raise Failure(f"{path} is not executable (chmod +x, and add a shebang)")
    except OSError as e:
        raise Failure(f"cannot execute {path}: {e}")


# --------------------------------------------------------------------------- #
# Skills
# --------------------------------------------------------------------------- #
def check_skill(skill_dir: Path, live: bool = False) -> None:
    manifest = skill_dir / "skill.yaml"
    if not manifest.exists():
        raise Failure("missing skill.yaml")
    m = yaml.safe_load(manifest.read_text()) or {}

    for field in ("name", "description", "version", "type"):
        if not m.get(field):
            raise Failure(f"skill.yaml missing '{field}'")
    if m["name"] != skill_dir.name:
        raise Failure(f"name '{m['name']}' != directory '{skill_dir.name}'")
    if m["type"] not in SKILL_TYPES:
        raise Failure(f"type must be one of {sorted(SKILL_TYPES)}, got '{m['type']}'")

    if m["type"] == "prompt":
        prompt_file = skill_dir / m.get("prompt", "prompt.md")
        if not prompt_file.exists() or not prompt_file.read_text().strip():
            raise Failure(f"prompt skill needs a non-empty {prompt_file.name}")
        return  # no code to run

    # script skill
    entry = m.get("entrypoint")
    if not entry:
        raise Failure("script skill missing 'entrypoint'")
    entry_path = skill_dir / entry
    if not entry_path.exists():
        raise Failure(f"entrypoint '{entry}' not found")
    if not isinstance(m.get("parameters"), dict):
        raise Failure("script skill missing 'parameters' (JSON-Schema object)")

    example = m.get("example") or {}
    payload = {"skill": m["name"], "input": example.get("input", {}), "context": CTX}

    # A skill that declares example.offline runs against its own canned fixtures, so CI
    # never reaches the internet to smoke-test a network skill. --live opts back in.
    env = {}
    if example.get("offline") and not live:
        if not m.get("permissions", {}).get("network"):
            raise Failure("example.offline is only meaningful for a network skill")
        env["SOUL_SKILL_OFFLINE"] = "1"
    proc = run_entrypoint(entry_path, payload, m.get("timeout_seconds", 10), env)
    if proc.returncode != 0:
        raise Failure(f"exit {proc.returncode}: {proc.stderr.strip()}")
    try:
        out = json.loads(proc.stdout)
    except json.JSONDecodeError:
        raise Failure(f"stdout is not JSON: {proc.stdout[:120]!r}")
    if out.get("ok") is not True or "output" not in out:
        raise Failure(f"result must have ok:true and output: {out}")


# --------------------------------------------------------------------------- #
# Hooks
# --------------------------------------------------------------------------- #
def _events(m: dict) -> list[str]:
    ev = m.get("event")
    return ev if isinstance(ev, list) else [ev]


def check_hook(hook_dir: Path) -> None:
    manifest = hook_dir / "hook.yaml"
    if not manifest.exists():
        raise Failure("missing hook.yaml")
    m = yaml.safe_load(manifest.read_text()) or {}

    for field in ("name", "description", "event", "entrypoint"):
        if not m.get(field):
            raise Failure(f"hook.yaml missing '{field}'")
    if m["name"] != hook_dir.name:
        raise Failure(f"name '{m['name']}' != directory '{hook_dir.name}'")
    for ev in _events(m):
        if ev not in HOOK_EVENTS:
            raise Failure(f"unknown event '{ev}'")
    entry_path = hook_dir / m["entrypoint"]
    if not entry_path.exists():
        raise Failure(f"entrypoint '{m['entrypoint']}' not found")

    blocking = bool(m.get("blocking", False))
    timeout = m.get("timeout_seconds", 10)

    # Declared example cases (event, payload, expect), else a generic benign case per event.
    cases = m.get("examples")
    if not cases:
        cases = [{"event": ev, "payload": {"skill": "echo", "input": {"text": "hi"}}, "expect": "allow"}
                 for ev in _events(m)]

    for case in cases:
        payload = {"event": case["event"], "payload": case.get("payload", {}), "context": CTX}
        proc = run_entrypoint(entry_path, payload, timeout)
        expect = case.get("expect", "allow")

        # Determine the effective action from exit code + optional stdout.
        stdout_action = None
        if proc.stdout.strip():
            try:
                parsed = json.loads(proc.stdout)
                stdout_action = parsed.get("action")
                if stdout_action not in HOOK_ACTIONS:
                    raise Failure(f"invalid action '{stdout_action}' in stdout")
                if stdout_action == "modify" and "patch" not in parsed:
                    raise Failure("modify action missing 'patch'")
            except json.JSONDecodeError:
                raise Failure(f"stdout is not JSON: {proc.stdout[:120]!r}")

        blocked = (proc.returncode != 0) or (stdout_action == "block")
        if expect == "block":
            if not blocking:
                raise Failure("example expects block but hook is not blocking:true")
            if not blocked:
                raise Failure(f"expected block, got exit {proc.returncode} action {stdout_action}")
        else:  # allow / modify
            if blocked:
                raise Failure(f"expected {expect}, but it blocked (exit {proc.returncode}): {proc.stderr.strip()}")
            if expect == "modify" and stdout_action != "modify":
                raise Failure(f"expected modify action, got {stdout_action}")


# --------------------------------------------------------------------------- #
def scan(pool: Path, marker: str, checker) -> list[dict]:
    results = []
    if not pool.exists():
        return results
    for child in sorted(pool.iterdir()):
        if not child.is_dir() or not (child / marker).exists():
            continue
        try:
            checker(child)  # noqa: PLW0108 - checker signature varies by pool
            results.append({"name": child.name, "ok": True})
        except Failure as e:
            results.append({"name": child.name, "ok": False, "error": str(e)})
        except Exception as e:  # noqa: BLE001 - report unexpected errors as failures
            results.append({"name": child.name, "ok": False, "error": f"unexpected: {e}"})
    return results


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Validate + smoke-test skillpool/ and hookspool/")
    p.add_argument("--json", action="store_true", help="machine-readable output")
    p.add_argument("--live", action="store_true",
                   help="let network skills reach the internet instead of their offline fixtures")
    args = p.parse_args(argv)

    skills = scan(SKILLPOOL, "skill.yaml", lambda d: check_skill(d, args.live))
    hooks = scan(HOOKSPOOL, "hook.yaml", check_hook)
    ok = all(r["ok"] for r in skills + hooks)

    if args.json:
        print(json.dumps({"ok": ok, "skills": skills, "hooks": hooks}, indent=2))
        return 0 if ok else 1

    for label, results in (("skillpool", skills), ("hookspool", hooks)):
        print(f"\n{label}/")
        if not results:
            print("  (none found)")
        for r in results:
            mark = "✓" if r["ok"] else "✗"
            print(f"  {mark} {r['name']}" + ("" if r["ok"] else f"  — {r['error']}"))
    total, failed = len(skills + hooks), sum(1 for r in skills + hooks if not r["ok"])
    print(f"\n{'OK' if ok else 'FAILED'}: {total - failed}/{total} passed")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
