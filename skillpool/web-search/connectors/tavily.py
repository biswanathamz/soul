"""Tavily provider — a search API built for LLM agents.

    TAVILY_API_KEY=<key>
"""

from __future__ import annotations

import os

from . import SearchError
from ._http import post_json


def search(query: str, max_results: int) -> list[dict]:
    key = (os.environ.get("TAVILY_API_KEY") or "").strip()
    if not key:
        raise SearchError("TAVILY_API_KEY is not set")

    payload = post_json(
        "https://api.tavily.com/search",
        {"api_key": key, "query": query, "max_results": max_results, "search_depth": "basic"},
    )
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
