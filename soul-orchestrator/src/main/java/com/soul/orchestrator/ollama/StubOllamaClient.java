package com.soul.orchestrator.ollama;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for a real model, active under the {@code stub-ollama}
 * profile. Lets the whole Manager loop — hooks, real skills, WS events — run and be
 * driven end-to-end without pulling a multi-GB model. Behavior:
 *
 * <ul>
 *   <li>if the last user turn mentions "time" and a {@code current-time} tool is
 *       offered and no tool result is present yet → request that tool;</li>
 *   <li>once a tool result is in the history, or otherwise → stream a short answer.</li>
 * </ul>
 */
@Component
@Profile("stub-ollama")
public class StubOllamaClient implements OllamaClient {

    @Override
    public ChatTurn chat(String model, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        boolean toolResultPresent = messages.stream().anyMatch(m -> "tool".equals(m.role()));
        String lastUser = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((a, b) -> b)
                .map(ChatMessage::content)
                .orElse("");
        boolean hasCurrentTime = tools.stream().anyMatch(t -> t.name().equals("current-time"));

        if (!toolResultPresent && hasCurrentTime && lastUser.toLowerCase().contains("time")) {
            return new ChatTurn("", List.of(new ToolCall("current-time", java.util.Map.of())));
        }

        String answer = toolResultPresent
                ? buildTimeAnswer(messages)
                : "Hello — I'm SOUL. (stub model: no local LLM running)";
        for (String word : answer.split("(?<= )")) {
            onToken.accept(word);
        }
        return new ChatTurn(answer, List.of());
    }

    private String buildTimeAnswer(List<ChatMessage> messages) {
        String toolOut = messages.stream()
                .filter(m -> "tool".equals(m.role()))
                .reduce((a, b) -> b)
                .map(ChatMessage::content)
                .orElse("");
        return "The current time is " + toolOut + ".";
    }

    @Override
    public List<String> listModels() {
        return List.of("llama3.1:8b", "qwen2.5:0.5b");
    }
}
