#!/usr/bin/env python3
"""web-search skill — find current information on the web.

Never calls a search engine directly: everything goes through the SearchConnector layer
(connectors/), so DuckDuckGo, SearXNG, Brave, Tavily or Google are one env var apart
(docs/researcher-agent.md §4.3).

Protocol: see skillpool/README.md.
  input.query       — what to search for (required)
  input.max_results — how many independent sources to return (default 5, capped 10)
  input.exclude_domains — domains to skip; the confidence policy's retry uses this to
                          force genuinely different sources on a second attempt
"""
import json
import os
import sys
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from connectors import SearchError, search  # noqa: E402


def _domain(url: str) -> str:
    return (urlparse(url).netloc or "").lower().removeprefix("www.")


def _independent(results: list[dict], limit: int, exclude: set[str]) -> list[dict]:
    """One result per domain — "multiple sources" only means something if they're independent."""
    seen: set[str] = set()
    out: list[dict] = []
    for result in results:
        domain = _domain(result["url"])
        if not domain or domain in seen or domain in exclude:
            continue
        seen.add(domain)
        out.append(result)
        if len(out) >= limit:
            break
    return out


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        req = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"web-search: invalid JSON on stdin: {e}", file=sys.stderr)
        return 1

    data = req.get("input") or {}
    query = str(data.get("query") or "").strip()
    if not query:
        print("web-search: 'query' is required", file=sys.stderr)
        return 1
    try:
        limit = max(1, min(int(data.get("max_results") or 5), 10))
    except (TypeError, ValueError):
        limit = 5
    exclude = {_domain(d) if "://" in d else d.lower().removeprefix("www.")
               for d in (data.get("exclude_domains") or [])}

    try:
        # Over-fetch: de-duplicating by domain trims the list, and excludes cut further.
        results, provider = search(query, limit * 3)
    except SearchError as e:
        print(f"web-search: no provider could answer: {e}", file=sys.stderr)
        return 1

    results = _independent(results, limit, exclude)
    if not results:
        output = f"No results for {query!r}."
    else:
        listed = "\n".join(
            f"{i}. {r['title']}\n   {r['url']}\n   {r['snippet']}".rstrip()
            for i, r in enumerate(results, 1)
        )
        output = f"Search results for {query!r} (via {provider}):\n{listed}"

    print(json.dumps({
        "ok": True,
        "output": output,
        "display": f"web-search: {len(results)} sources via {provider}",
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
