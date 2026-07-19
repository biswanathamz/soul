"""SearXNG provider — self-hosted metasearch.

The privacy-preserving fallback: queries go to an instance you run, and it is already
multi-engine, which strengthens the "independent sources" goal on its own.

    SEARXNG_URL=http://localhost:8888
"""

from __future__ import annotations

import os

from . import SearchError
from ._http import get_json


def search(query: str, max_results: int) -> list[dict]:
    base = (os.environ.get("SEARXNG_URL") or "").strip().rstrip("/")
    if not base:
        raise SearchError("SEARXNG_URL is not set")

    payload = get_json(f"{base}/search", {"q": query, "format": "json", "language": "en"})
    results = []
    for item in (payload.get("results") or [])[:max_results]:
        url = item.get("url")
        if not url:
            continue
        results.append({
            "title": item.get("title") or url,
            "url": url,
            "snippet": item.get("content") or "",
        })
    return results
