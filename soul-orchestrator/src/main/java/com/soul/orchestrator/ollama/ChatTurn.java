package com.soul.orchestrator.ollama;

import java.util.List;

/**
 * The result of one model call: either free-text {@code content} (a final answer)
 * or a set of {@code toolCalls} the model wants run. When tool calls are present the
 * loop executes them and calls the model again.
 */
public record ChatTurn(String content, List<ToolCall> toolCalls) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
