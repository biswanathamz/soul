"""Offline fixture provider — canned results, never the network.

Selected automatically when SOUL_SKILL_OFFLINE=1, which is what pooltest.py sets so CI
can smoke-test a network skill without reaching the internet (run pooltest with --live
to exercise the real providers locally).
"""

from __future__ import annotations

FIXTURE = [
    {
        "title": "Node.js — Run JavaScript Everywhere",
        "url": "https://nodejs.org/en/about/previous-releases",
        "snippet": "Node.js 22 'Jod' is the Active LTS release line.",
    },
    {
        "title": "Node.js (endoflife.date)",
        "url": "https://endoflife.date/nodejs",
        "snippet": "Node.js 22 entered Active LTS in October 2024.",
    },
    {
        "title": "Node.js Releases — GitHub",
        "url": "https://github.com/nodejs/release",
        "snippet": "Release schedule and LTS codenames for every Node.js line.",
    },
]


def search(query: str, max_results: int) -> list[dict]:
    return FIXTURE[:max_results]
