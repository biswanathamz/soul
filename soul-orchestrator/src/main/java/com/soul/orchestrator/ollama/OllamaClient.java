package com.soul.orchestrator.ollama;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Manager's window onto the local model. Kept as an interface so the agentic
 * loop can be driven by a real Ollama server or a deterministic stub in tests.
 */
public interface OllamaClient {

    /**
     * One chat turn. Streams content deltas to {@code onToken} as they arrive and
     * returns the assembled turn (final text, or the tool calls the model requested).
     */
    ChatTurn chat(String model, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken);

    /** Installed model names (Ollama /api/tags), for GET /api/v1/models. */
    List<String> listModels();
}
