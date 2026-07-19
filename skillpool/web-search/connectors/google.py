"""Google Programmable Search provider.

    GOOGLE_CSE_KEY=<api key>
    GOOGLE_CSE_ID=<search engine id>
"""

from __future__ import annotations

import os

from . import SearchError
from ._http import get_json


def search(query: str, max_results: int) -> list[dict]:
    key = (os.environ.get("GOOGLE_CSE_KEY") or "").strip()
    engine = (os.environ.get("GOOGLE_CSE_ID") or "").strip()
    if not key or not engine:
        raise SearchError("GOOGLE_CSE_KEY and GOOGLE_CSE_ID must both be set")

    payload = get_json(
        "https://www.googleapis.com/customsearch/v1",
        {"key": key, "cx": engine, "q": query, "num": min(max_results, 10)},
    )
    results = []
    for item in (payload.get("items") or [])[:max_results]:
        url = item.get("link")
        if not url:
            continue
        results.append({
            "title": item.get("title") or url,
            "url": url,
            "snippet": item.get("snippet") or "",
        })
    return results
