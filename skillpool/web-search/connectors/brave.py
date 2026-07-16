"""Brave Search provider.

    BRAVE_API_KEY=<key>
"""

from __future__ import annotations

import os

from . import SearchError
from ._http import get_json


def search(query: str, max_results: int) -> list[dict]:
    key = (os.environ.get("BRAVE_API_KEY") or "").strip()
    if not key:
        raise SearchError("BRAVE_API_KEY is not set")

    payload = get_json(
        "https://api.search.brave.com/res/v1/web/search",
        {"q": query, "count": max_results},
        {"Accept": "application/json", "X-Subscription-Token": key},
    )
    results = []
    for item in ((payload.get("web") or {}).get("results") or [])[:max_results]:
        url = item.get("url")
        if not url:
            continue
        results.append({
            "title": item.get("title") or url,
            "url": url,
            "snippet": item.get("description") or "",
        })
    return results
