package com.soul.orchestrator.ollama;

import java.util.List;

/**
 * One message in an Ollama chat exchange. {@code toolCalls} is set on an assistant
 * message that requested tools; a tool-result message uses role {@code "tool"} with
 * the skill output as {@code content}.
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, List.of());
    }

    public static ChatMessage assistantToolCalls(List<ToolCall> calls) {
        return new ChatMessage("assistant", "", calls);
    }

    public static ChatMessage toolResult(String content) {
        return new ChatMessage("tool", content, List.of());
    }
}
