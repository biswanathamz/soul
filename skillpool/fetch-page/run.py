#!/usr/bin/env python3
"""fetch-page skill — fetch a URL and return its readable text.

Strips scripts, styles and nav/boilerplate heuristically, and truncates hard: a model
reading four sources cannot afford a 200 kB page. Standard library only, so the skill
stays dependency-free.

Safety: http(s) only, no cross-scheme redirects, a byte cap enforced while streaming
(not after), and a wall-clock timeout.

Protocol: see skillpool/README.md.
  input.url       — the page to read (required)
  input.max_chars — truncation limit (default 6000, capped 20000)

The output starts with stable `Title:` / `URL:` lines — the Researcher reads those back
to cite its sources.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from html.parser import HTMLParser
from urllib.parse import urlparse

USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) SOUL/0.1 (+https://github.com/biswanathamz/soul)"
TIMEOUT_SECONDS = 15
MAX_BYTES = 2_000_000  # refuse to download more than this, however long the page claims to be
SKIP_TAGS = {"script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg"}
BLOCK_TAGS = {"p", "div", "section", "article", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6"}


class _TextExtractor(HTMLParser):
    """Collect visible text; drop chrome. Crude on purpose — good enough to read prose."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title = ""
        self._chunks: list[str] = []
        self._skip_depth = 0
        self._in_title = False

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag in SKIP_TAGS:
            self._skip_depth += 1
        elif tag == "title":
            self._in_title = True
        elif tag in BLOCK_TAGS:
            self._chunks.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in SKIP_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)
        elif tag == "title":
            self._in_title = False
        elif tag in BLOCK_TAGS:
            self._chunks.append("\n")

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title += data
        elif self._skip_depth == 0 and data.strip():
            self._chunks.append(data)

    def text(self) -> str:
        joined = "".join(self._chunks)
        joined = re.sub(r"[ \t\r\f\v]+", " ", joined)
        joined = re.sub(r"\n\s*\n\s*", "\n\n", joined)  # collapse blank runs
        return "\n".join(line.strip() for line in joined.splitlines()).strip()


def main() -> int:
    raw = sys.stdin.read() or "{}"
    try:
        req = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"fetch-page: invalid JSON on stdin: {e}", file=sys.stderr)
        return 1

    data = req.get("input") or {}
    url = str(data.get("url") or "").strip()
    if not url:
        print("fetch-page: 'url' is required", file=sys.stderr)
        return 1
    if urlparse(url).scheme not in ("http", "https"):
        print(f"fetch-page: refusing non-http(s) URL: {url}", file=sys.stderr)
        return 1
    try:
        max_chars = max(500, min(int(data.get("max_chars") or 6000), 20000))
    except (TypeError, ValueError):
        max_chars = 6000

    if os.environ.get("SOUL_SKILL_OFFLINE") == "1":
        return _offline(url)

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html,*/*"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            if urlparse(response.geturl()).scheme not in ("http", "https"):
                print("fetch-page: refusing off-scheme redirect", file=sys.stderr)
                return 1
            content_type = (response.headers.get("Content-Type") or "").lower()
            if content_type and "html" not in content_type and "text" not in content_type:
                print(f"fetch-page: unsupported content type: {content_type}", file=sys.stderr)
                return 1
            body = response.read(MAX_BYTES)  # cap while reading, not after
            final_url = response.geturl()
    except urllib.error.HTTPError as e:
        print(f"fetch-page: HTTP {e.code} for {url}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        print(f"fetch-page: cannot fetch {url}: {e}", file=sys.stderr)
        return 1

    parser = _TextExtractor()
    parser.feed(body.decode("utf-8", "replace"))
    text = parser.text()
    truncated = len(text) > max_chars
    if truncated:
        text = text[:max_chars].rsplit(" ", 1)[0] + " …[truncated]"

    title = re.sub(r"\s+", " ", parser.title).strip() or urlparse(final_url).netloc
    print(json.dumps({
        "ok": True,
        "output": f"Title: {title}\nURL: {final_url}\n\n{text}",
        "display": f"fetch-page: {title[:60]} ({len(text)} chars)",
    }))
    return 0


def _offline(url: str) -> int:
    """Canned page so pooltest/CI can smoke-test without the network."""
    print(json.dumps({
        "ok": True,
        "output": (
            "Title: Node.js — Previous Releases\n"
            f"URL: {url}\n\n"
            "Node.js 22 'Jod' is the Active LTS release line as of October 2024."
        ),
        "display": "fetch-page: Node.js — Previous Releases (offline fixture)",
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
