package com.soul.orchestrator.agent;

/**
 * Thrown from the loop's token consumer to abort a generation already in flight
 * (docs/researcher-agent.md §3.5). Step-boundary checks alone would leave a cancelled
 * worker streaming for the rest of a model call — tens of seconds on a 4 GB GPU — so the
 * loop bails out of the stream itself and lets the HTTP connection close.
 */
class LoopCancelledException extends RuntimeException {

    LoopCancelledException() {
        super("cancelled");
    }
}
