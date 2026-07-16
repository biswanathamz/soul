package com.soul.orchestrator.agent;

import com.soul.orchestrator.ollama.ToolCall;

/**
 * Watches a loop run without steering it. The Researcher uses this to narrate its work as
 * staged progress (docs/researcher-agent.md §3.2c) — "Searching the web…", "Found 3
 * sources", "Reading nodejs.org (1/3)" — while the loop itself stays ignorant of what
 * research is. Stage vocabulary belongs to the worker; the loop only reports what it did.
 */
public interface LoopObserver {

    LoopObserver NONE = new LoopObserver() { };

    /** Before each model call. {@code step} is 0-based. */
    default void onStep(int step) {
    }

    /** A tool is about to run — after the hook gates cleared it. */
    default void onToolCall(ToolCall call) {
    }

    /** A tool finished; {@code output} is what the model will see. */
    default void onToolResult(ToolCall call, String output) {
    }
}
