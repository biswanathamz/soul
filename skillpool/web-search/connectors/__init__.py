"""The SearchConnector layer — the skill never calls a provider directly.

One interface, pluggable providers, chosen by environment (injected by the
orchestrator's Runner from SOUL's config, docs/researcher-agent.md §4.3):

    SOUL_SEARCH_PROVIDER=duckduckgo     # primary
    SOUL_SEARCH_FALLBACKS=searxng,brave # tried in order if the primary can't answer

Every provider is one small module exposing ``search(query, max_results) -> [Result]``
and raising :class:`SearchError` when it cannot answer. Adding one never touches the
skill's contract, the Researcher, or the Manager — exactly the isolation the Gmail
connector idea gives integrations.

A Result is ``{"title": str, "url": str, "snippet": str}``.
"""

from __future__ import annotations

import importlib
import os

DEFAULT_PROVIDER = "duckduckgo"
PROVIDERS = ("duckduckgo", "searxng", "brave", "tavily", "google", "offline")


class SearchError(Exception):
    """A provider could not answer; the chain advances to the next one."""


def _load(name: str):
    if name not in PROVIDERS:
        raise SearchError(f"unknown provider '{name}' (known: {', '.join(PROVIDERS)})")
    return importlib.import_module(f"connectors.{name}")


def chain() -> list[str]:
    """Provider order for this run: primary first, then fallbacks, de-duplicated."""
    if os.environ.get("SOUL_SKILL_OFFLINE") == "1":
        return ["offline"]  # CI and pooltest: canned fixtures, never the network
    primary = (os.environ.get("SOUL_SEARCH_PROVIDER") or "").strip() or DEFAULT_PROVIDER
    raw = (os.environ.get("SOUL_SEARCH_FALLBACKS") or "").split(",")
    order = [primary]
    for name in (p.strip() for p in raw):
        if name and name not in order:
            order.append(name)
    return order


def search(query: str, max_results: int) -> tuple[list[dict], str]:
    """Search via the first provider that answers.

    Returns ``(results, provider_name)`` — the caller reports which provider answered, so
    a silent fallback is still visible. Raises :class:`SearchError` if the whole chain
    fails, carrying every provider's reason.
    """
    reasons = []
    for name in chain():
        try:
            results = _load(name).search(query, max_results)
        except SearchError as e:
            reasons.append(f"{name}: {e}")
            continue
        except Exception as e:  # a broken provider must not take the chain down
            reasons.append(f"{name}: {type(e).__name__}: {e}")
            continue
        if results:
            return results, name
        reasons.append(f"{name}: no results")
    raise SearchError("; ".join(reasons) or "no providers configured")
