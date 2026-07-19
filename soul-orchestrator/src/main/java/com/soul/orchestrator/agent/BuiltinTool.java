package com.soul.orchestrator.agent;

import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.ollama.ToolSpec;
import java.util.function.Function;

/**
 * A tool the orchestrator implements itself rather than a pool skill. Delegation is
 * orchestration, not a skill, so the Manager injects its {@code delegate} tool this way
 * (docs/researcher-agent.md §5) — it still passes through the same {@code before_skill} /
 * {@code after_skill} hooks as any script skill, so safety gates apply uniformly.
 */
public record BuiltinTool(ToolSpec spec, Function<ToolCall, String> handler) {

    public String name() {
        return spec.name();
    }
}
