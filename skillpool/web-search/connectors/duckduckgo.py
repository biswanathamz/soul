"""DuckDuckGo provider — the zero-setup default: no API key, no account, no config.

Uses the HTML endpoint and parses it with the standard library, so skills stay
dependency-free (a skill is executed by its shebang against the system Python).
"""

from __future__ import annotations

import re
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

from . import SearchError

ENDPOINT = "https://html.duckduckgo.com/html/"
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) SOUL/0.1 (+https://github.com/biswanathamz/soul)"
TIMEOUT_SECONDS = 12


def _unwrap(href: str | None) -> str | None:
    """DuckDuckGo wraps every result in a /l/?uddg=<real-url> redirect."""
    if not href:
        return None
    if href.startswith("//"):
        href = "https:" + href
    parsed = urllib.parse.urlparse(href)
    if "duckduckgo.com" in parsed.netloc and parsed.path.startswith("/l/"):
        target = urllib.parse.parse_qs(parsed.query).get("uddg")
        return target[0] if target else None
    return href if parsed.scheme in ("http", "https") else None


class _ResultParser(HTMLParser):
    """Pulls {title, url, snippet} out of DDG's result markup."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.results: list[dict] = []
        self._mode: str | None = None
        self._href: str | None = None
        self._buf: list[str] = []

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag != "a":
            return
        classes = dict(attrs).get("class") or ""
        if "result__a" in classes:
            self._mode, self._href, self._buf = "title", dict(attrs).get("href"), []
        elif "result__snippet" in classes:
            self._mode, self._buf = "snippet", []

    def handle_data(self, data: str) -> None:
        if self._mode:
            self._buf.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag != "a" or not self._mode:
            return
        text = re.sub(r"\s+", " ", "".join(self._buf)).strip()
        if self._mode == "title":
            url = _unwrap(self._href)
            if url:
                self.results.append({"title": text, "url": url, "snippet": ""})
        elif self._mode == "snippet" and self.results:
            self.results[-1]["snippet"] = text
        self._mode, self._buf = None, []


def search(query: str, max_results: int) -> list[dict]:
    body = urllib.parse.urlencode({"q": query, "kl": "wt-wt"}).encode()
    request = urllib.request.Request(ENDPOINT, data=body, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            html = response.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        raise SearchError(f"request failed: {e}") from e

    parser = _ResultParser()
    parser.feed(html)
    return parser.results[:max_results]
