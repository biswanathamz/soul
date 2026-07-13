#!/usr/bin/env python3
"""SOUL — Ollama model manager.

Reconciles a running Ollama instance against a declarative manifest
(``models.yaml``). Talks only to Ollama's REST API, so it behaves identically
against the compose container, a host install, or a remote box.

    python manage.py status
    python manage.py sync
    python manage.py verify

See ``README.md`` (or ``docs/ollama-model-management.md``) for the full design.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

try:
    import yaml
except ImportError:  # pragma: no cover - trivial guard
    sys.stderr.write("PyYAML is required: pip install pyyaml\n")
    sys.exit(2)

DEFAULT_HOST = os.environ.get("OLLAMA_HOST", "http://localhost:11434")
DEFAULT_MANIFEST = Path(
    os.environ.get("SOUL_MODELS_MANIFEST", Path(__file__).with_name("models.yaml"))
)
DEFAULT_DATA_PATH = os.environ.get("OLLAMA_DATA_PATH", "~/.ollama")
KNOWN_ROLES = {"super", "coder", "researcher", "writer", "analyst", "sysops"}
# Hosts a destructive command (rm/prune) may target without an override flag.
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "[::1]", "soul-ollama"}
PULL_RETRIES = 3


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #
def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} PB"


def normalize_host(raw: str) -> str:
    raw = raw.strip()
    if "://" not in raw:
        raw = "http://" + raw
    return raw.rstrip("/")


def host_is_local(host: str) -> bool:
    from urllib.parse import urlparse

    return (urlparse(host).hostname or "") in LOCAL_HOSTS


def is_pinned(name: str) -> bool:
    """True if the model tag is explicit and not the drift-prone 'latest'."""
    if ":" not in name:
        return False
    tag = name.split(":", 1)[1]
    return tag not in ("", "latest")


def base_name(name: str) -> str:
    return name.split(":", 1)[0]


# --------------------------------------------------------------------------- #
# Ollama REST client
# --------------------------------------------------------------------------- #
class OllamaError(Exception):
    pass


@dataclass
class Ollama:
    host: str

    def _request(
        self, path: str, *, method: str = "GET", body: dict | None = None, timeout: float | None = 10
    ) -> urllib.request.Request:
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if data else {}
        return urllib.request.Request(f"{self.host}{path}", data=data, method=method, headers=headers)

    def _json(self, path: str, *, method: str = "GET", body: dict | None = None) -> dict:
        try:
            with urllib.request.urlopen(self._request(path, method=method, body=body), timeout=15) as r:
                raw = r.read().decode() or "{}"
                return json.loads(raw)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                detail = json.loads(detail).get("error", detail)
            except Exception:
                pass
            raise OllamaError(f"{method} {path} -> HTTP {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            raise OllamaError(f"cannot reach Ollama at {self.host}: {e.reason}") from e

    def reachable(self) -> bool:
        try:
            self._json("/api/version")
            return True
        except OllamaError:
            return False

    def tags(self) -> list[dict]:
        return self._json("/api/tags").get("models", [])

    def ps(self) -> list[dict]:
        return self._json("/api/ps").get("models", [])

    def delete(self, model: str) -> None:
        self._json("/api/delete", method="DELETE", body={"model": model})

    def load(self, model: str, keep_alive: str) -> None:
        """Load a model into memory (empty generation) so first use is instant."""
        self._json("/api/generate", method="POST", body={"model": model, "keep_alive": keep_alive, "stream": False})

    def pull(self, model: str) -> Iterator[dict]:
        """Stream pull progress events (NDJSON) from Ollama."""
        req = self._request("/api/pull", method="POST", body={"model": model, "stream": True})
        try:
            with urllib.request.urlopen(req, timeout=None) as resp:
                for raw in resp:
                    raw = raw.strip()
                    if not raw:
                        continue
                    evt = json.loads(raw)
                    if "error" in evt:
                        raise OllamaError(evt["error"])
                    yield evt
        except urllib.error.HTTPError as e:
            raise OllamaError(f"pull {model} -> HTTP {e.code}: {e.read().decode(errors='replace')}") from e
        except urllib.error.URLError as e:
            raise OllamaError(f"cannot reach Ollama at {self.host}: {e.reason}") from e


# --------------------------------------------------------------------------- #
# Manifest
# --------------------------------------------------------------------------- #
@dataclass
class ModelSpec:
    name: str
    roles: list[str]
    required: bool
    warm: bool
    keep_alive: str


def load_manifest(path: Path) -> list[ModelSpec]:
    if not path.exists():
        raise SystemExit(f"manifest not found: {path}")
    data = yaml.safe_load(path.read_text()) or {}
    default_keep = (data.get("defaults") or {}).get("keep_alive", "30m")
    specs: list[ModelSpec] = []
    for entry in data.get("models") or []:
        if "name" not in entry:
            raise SystemExit(f"manifest entry missing 'name': {entry!r}")
        specs.append(
            ModelSpec(
                name=entry["name"],
                roles=list(entry.get("roles") or []),
                required=bool(entry.get("required", False)),
                warm=bool(entry.get("warm", False)),
                keep_alive=entry.get("keep_alive", default_keep),
            )
        )
    return specs


# --------------------------------------------------------------------------- #
# Pull progress rendering
# --------------------------------------------------------------------------- #
class Progress:
    """Live per-model pull progress: a CR-updated line on a TTY, one line per
    10% otherwise (so compose logs stay readable)."""

    def __init__(self, model: str, quiet: bool) -> None:
        self.model = model
        self.quiet = quiet
        self.tty = sys.stdout.isatty()
        self.layers: dict[str, tuple[int, int]] = {}
        self.last_bucket = -1

    def update(self, evt: dict) -> None:
        if self.quiet:
            return
        digest, total = evt.get("digest"), evt.get("total")
        if digest and total:
            self.layers[digest] = (evt.get("completed", 0), total)
        completed = sum(c for c, _ in self.layers.values())
        total_all = sum(t for _, t in self.layers.values())
        if total_all <= 0:
            if self.tty:
                sys.stdout.write(f"\r  {self.model}: {evt.get('status', '')}".ljust(60))
                sys.stdout.flush()
            return
        pct = completed / total_all * 100
        if self.tty:
            sys.stdout.write(f"\r  pulling {self.model} … {pct:5.1f}%  {human(completed)}/{human(total_all)}   ")
            sys.stdout.flush()
        else:
            bucket = int(pct // 10)
            if bucket > self.last_bucket:
                self.last_bucket = bucket
                print(f"  pulling {self.model} … {pct:.0f}%  ({human(completed)}/{human(total_all)})")

    def finish(self) -> None:
        if self.tty and not self.quiet:
            sys.stdout.write("\n")
            sys.stdout.flush()


# --------------------------------------------------------------------------- #
# Shared operations
# --------------------------------------------------------------------------- #
def installed_names(client: Ollama) -> set[str]:
    return {m["name"] for m in client.tags()}


def check_disk(data_path: str, min_free_gb: float, quiet: bool) -> None:
    path = Path(data_path).expanduser()
    if not path.exists():
        if not quiet:
            eprint(f"  disk guard skipped: {path} not found (relying on Ollama's own errors)")
        return
    free_gb = shutil.disk_usage(path).free / 1e9
    if free_gb < min_free_gb:
        raise SystemExit(f"disk guard: only {free_gb:.1f} GB free at {path}, need >= {min_free_gb:.0f} GB")
    if not quiet:
        print(f"  disk guard: {free_gb:.1f} GB free at {path} (>= {min_free_gb:.0f} GB) ✓")


def pull_with_retries(client: Ollama, model: str, quiet: bool) -> None:
    last: Exception | None = None
    for attempt in range(1, PULL_RETRIES + 1):
        try:
            progress = Progress(model, quiet)
            for evt in client.pull(model):
                progress.update(evt)
            progress.finish()
            return
        except OllamaError as e:
            last = e
            if attempt < PULL_RETRIES:
                backoff = 2**attempt
                eprint(f"  pull {model} failed (attempt {attempt}/{PULL_RETRIES}): {e}; retrying in {backoff}s")
                time.sleep(backoff)
    raise OllamaError(f"pull {model} failed after {PULL_RETRIES} attempts: {last}")


def assert_safe_host(host: str, force: bool, action: str) -> None:
    if host_is_local(host) or force:
        return
    raise SystemExit(
        f"refusing to {action} against non-local host {host}. "
        f"Pass --i-know-what-im-doing to override."
    )


# --------------------------------------------------------------------------- #
# Commands
# --------------------------------------------------------------------------- #
def cmd_status(args: argparse.Namespace) -> int:
    client = Ollama(args.host)
    specs = load_manifest(args.manifest)
    try:
        tags = {m["name"]: m for m in client.tags()}
        loaded = {m["name"] for m in client.ps()}
        reachable = True
    except OllamaError as e:
        if args.json:
            print(json.dumps({"reachable": False, "error": str(e)}))
            return 1
        eprint(f"Ollama unreachable: {e}")
        tags, loaded, reachable = {}, set(), False

    rows = []
    manifest_names = {s.name for s in specs}
    for s in specs:
        tag = tags.get(s.name)
        rows.append(
            {
                "name": s.name,
                "installed": tag is not None,
                "size": tag.get("size") if tag else None,
                "digest": (tag.get("digest", "")[:12] if tag else None),
                "roles": s.roles,
                "required": s.required,
                "warm": s.warm,
                "loaded": s.name in loaded,
            }
        )
    extras = [
        {"name": name, "size": t.get("size"), "loaded": name in loaded}
        for name, t in tags.items()
        if name not in manifest_names
    ]

    if args.json:
        print(json.dumps({"reachable": reachable, "models": rows, "unmanaged": extras}, indent=2))
        return 0

    print(f"Ollama: {args.host}\n")
    print(f"{'MODEL':<22}{'INSTALLED':<11}{'SIZE':<11}{'LOADED':<8}{'ROLES'}")
    print("-" * 72)
    for r in rows:
        inst = "yes" if r["installed"] else ("MISSING" if r["required"] else "no")
        size = human(r["size"]) if r["size"] else "—"
        loaded_s = "●" if r["loaded"] else "—"
        roles = ",".join(r["roles"]) or ("(unused)" if r["installed"] else "—")
        print(f"{r['name']:<22}{inst:<11}{size:<11}{loaded_s:<8}{roles}")
    if extras:
        print("\nUnmanaged (not in manifest — candidates for prune):")
        for e in extras:
            print(f"  {e['name']:<22}{human(e['size']) if e['size'] else '—'}")
    return 0


def cmd_sync(args: argparse.Namespace) -> int:
    client = Ollama(args.host)
    specs = load_manifest(args.manifest)
    if not client.reachable():
        raise SystemExit(f"Ollama unreachable at {args.host}")

    check_disk(args.data_path, args.min_free_gb, args.json)
    have = installed_names(client)
    want = [s for s in specs if s.required or s.warm]
    to_pull = [s for s in want if s.name not in have]
    to_warm = [s for s in specs if s.warm]

    result: dict[str, Any] = {"pulled": [], "warmed": [], "already": [s.name for s in want if s.name in have]}

    if not args.json:
        print(f"sync: {len(to_pull)} to pull, {len(to_warm)} to warm, {len(result['already'])} already present\n")
    for s in to_pull:
        if not args.json:
            print(f"→ {s.name}")
        pull_with_retries(client, s.name, args.json)
        result["pulled"].append(s.name)

    for s in to_warm:
        try:
            client.load(s.name, s.keep_alive)
            result["warmed"].append(s.name)
            if not args.json:
                print(f"  warmed {s.name} (keep_alive={s.keep_alive})")
        except OllamaError as e:
            eprint(f"  warm {s.name} failed: {e}")

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"\nsync complete: pulled {len(result['pulled'])}, warmed {len(result['warmed'])}")
    return 0


def cmd_pull(args: argparse.Namespace) -> int:
    client = Ollama(args.host)
    specs = {s.name for s in load_manifest(args.manifest)}
    if args.model not in specs and not args.force:
        raise SystemExit(f"{args.model} is not in the manifest. Add it, or pass --force.")
    check_disk(args.data_path, args.min_free_gb, args.json)
    pull_with_retries(client, args.model, args.json)
    if args.json:
        print(json.dumps({"pulled": args.model}))
    else:
        print(f"pulled {args.model}")
    return 0


def cmd_rm(args: argparse.Namespace) -> int:
    client = Ollama(args.host)
    specs = {s.name: s for s in load_manifest(args.manifest)}
    assert_safe_host(args.host, args.i_know_what_im_doing, "remove models")
    spec = specs.get(args.model)
    if spec and spec.required and not args.force:
        raise SystemExit(f"{args.model} is marked required in the manifest. Pass --force to remove it anyway.")
    client.delete(args.model)
    if args.json:
        print(json.dumps({"removed": args.model}))
    else:
        print(f"removed {args.model}")
    return 0


def cmd_prune(args: argparse.Namespace) -> int:
    client = Ollama(args.host)
    manifest_names = {s.name for s in load_manifest(args.manifest)}
    assert_safe_host(args.host, args.i_know_what_im_doing, "prune models")
    extras = [name for name in installed_names(client) if name not in manifest_names]
    if not extras:
        if args.json:
            print(json.dumps({"removed": []}))
        else:
            print("nothing to prune — all installed models are in the manifest")
        return 0
    if not args.yes and not args.json:
        print("The following models are not in the manifest and will be removed:")
        for name in extras:
            print(f"  {name}")
        if input("Proceed? [y/N] ").strip().lower() not in ("y", "yes"):
            print("aborted")
            return 1
    for name in extras:
        client.delete(name)
        if not args.json:
            print(f"removed {name}")
    if args.json:
        print(json.dumps({"removed": extras}))
    return 0


def cmd_warm(args: argparse.Namespace) -> int:
    client = Ollama(args.host)
    specs = load_manifest(args.manifest)
    if args.models:
        targets = [s for s in specs if s.name in args.models]
        unknown = set(args.models) - {s.name for s in targets}
        if unknown:
            raise SystemExit(f"not in manifest: {', '.join(sorted(unknown))}")
    else:
        targets = [s for s in specs if s.warm]
    warmed = []
    for s in targets:
        client.load(s.name, s.keep_alive)
        warmed.append(s.name)
        if not args.json:
            print(f"warmed {s.name} (keep_alive={s.keep_alive})")
    if args.json:
        print(json.dumps({"warmed": warmed}))
    return 0


def _read_orchestrator_bindings() -> dict[str, str] | None:
    """Return role->model from the orchestrator config, or None if absent."""
    cfg = Path(__file__).resolve().parents[2] / "soul-orchestrator/src/main/resources/application.yml"
    if not cfg.exists():
        return None
    data = yaml.safe_load(cfg.read_text()) or {}
    soul = data.get("soul") or {}
    bindings: dict[str, str] = {}
    if (sa := soul.get("super-agent")) and sa.get("model"):
        bindings["super"] = sa["model"]
    for role, spec in (soul.get("agents") or {}).items():
        if isinstance(spec, dict) and spec.get("model"):
            bindings[role] = spec["model"]
    return bindings


def cmd_verify(args: argparse.Namespace) -> int:
    specs = load_manifest(args.manifest)
    errors: list[str] = []
    warnings: list[str] = []

    # --- static checks (no Ollama needed) ---
    seen: set[str] = set()
    for s in specs:
        if s.name in seen:
            errors.append(f"duplicate manifest entry: {s.name}")
        seen.add(s.name)
        if not is_pinned(s.name):
            errors.append(f"unpinned tag (use an explicit version, not :latest): {s.name}")
        for role in s.roles:
            if role not in KNOWN_ROLES:
                warnings.append(f"unknown role '{role}' on {s.name}")

    manifest_bases = {base_name(s.name) for s in specs}
    manifest_names = {s.name for s in specs}
    bindings = _read_orchestrator_bindings()
    if bindings is None:
        warnings.append("orchestrator application.yml not found — agent bindings unchecked")
    else:
        for role, model in bindings.items():
            if model not in manifest_names and base_name(model) not in manifest_bases:
                errors.append(f"orchestrator binds {role} -> {model}, which is not in the manifest")

    # --- live check (required models installed) ---
    live_checked = False
    client = Ollama(args.host)
    if client.reachable():
        have = installed_names(client)
        live_checked = True
        for s in specs:
            if s.required and s.name not in have:
                errors.append(f"required model not installed: {s.name}")
    elif args.require_live:
        errors.append(f"Ollama unreachable at {args.host} (--require-live)")
    else:
        warnings.append(f"Ollama unreachable at {args.host} — install checks skipped")

    if args.json:
        print(json.dumps({"ok": not errors, "errors": errors, "warnings": warnings, "live_checked": live_checked}, indent=2))
    else:
        for w in warnings:
            eprint(f"war: {w}")
        for e in errors:
            eprint(f"error: {e}")
        print("verify: OK" if not errors else f"verify: FAILED ({len(errors)} error(s))")
    return 0 if not errors else 1


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="manage.py", description="SOUL Ollama model manager")
    p.add_argument("--host", default=DEFAULT_HOST, help=f"Ollama base URL (default {DEFAULT_HOST})")
    p.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST, help="path to models.yaml")
    p.add_argument("--json", action="store_true", help="machine-readable output")
    p.add_argument("--data-path", default=DEFAULT_DATA_PATH, help="Ollama data dir for the disk guard")
    p.add_argument("--min-free-gb", type=float, default=10.0, help="minimum free space before pulling")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("status", help="show manifest vs installed models")

    sub.add_parser("sync", help="pull missing required models and warm the warm ones")

    sp = sub.add_parser("pull", help="pull a single model")
    sp.add_argument("model")
    sp.add_argument("--force", action="store_true", help="allow pulling a model not in the manifest")

    sr = sub.add_parser("rm", help="remove a single model")
    sr.add_argument("model")
    sr.add_argument("--force", action="store_true", help="allow removing a required model")
    sr.add_argument("--i-know-what-im-doing", action="store_true", help="allow non-local host")

    spr = sub.add_parser("prune", help="remove installed models not in the manifest")
    spr.add_argument("--yes", action="store_true", help="skip confirmation")
    spr.add_argument("--i-know-what-im-doing", action="store_true", help="allow non-local host")

    sw = sub.add_parser("warm", help="load models into RAM (default: all warm:true)")
    sw.add_argument("models", nargs="*", help="specific models to warm")

    sv = sub.add_parser("verify", help="CI check: manifest + bindings + required installed")
    sv.add_argument("--require-live", action="store_true", help="fail if Ollama is unreachable")

    return p


COMMANDS = {
    "status": cmd_status,
    "sync": cmd_sync,
    "pull": cmd_pull,
    "rm": cmd_rm,
    "prune": cmd_prune,
    "warm": cmd_warm,
    "verify": cmd_verify,
}


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    args.host = normalize_host(args.host)
    try:
        return COMMANDS[args.command](args)
    except OllamaError as e:
        eprint(f"error: {e}")
        return 1
    except KeyboardInterrupt:
        eprint("\ninterrupted (Ollama resumes partial pulls on the next run)")
        return 130


if __name__ == "__main__":
    sys.exit(main())
