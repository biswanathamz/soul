"""Shared JSON-over-HTTP helper for the API-backed providers."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

from . import SearchError

TIMEOUT_SECONDS = 12


def get_json(url: str, params: dict, headers: dict | None = None) -> dict:
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    request = urllib.request.Request(f"{url}?{query}", headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise SearchError(f"HTTP {e.code}") from e
    except (urllib.error.URLError, OSError, TimeoutError, json.JSONDecodeError) as e:
        raise SearchError(f"request failed: {e}") from e


def post_json(url: str, body: dict, headers: dict | None = None) -> dict:
    data = json.dumps(body).encode()
    merged = {"Content-Type": "application/json", **(headers or {})}
    request = urllib.request.Request(url, data=data, headers=merged)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise SearchError(f"HTTP {e.code}") from e
    except (urllib.error.URLError, OSError, TimeoutError, json.JSONDecodeError) as e:
        raise SearchError(f"request failed: {e}") from e
