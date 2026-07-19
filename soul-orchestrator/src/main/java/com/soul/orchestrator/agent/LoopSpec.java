package com.soul.orchestrator.agent;

import com.soul.orchestrator.ollama.ChatMessage;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One run of the {@link AgentLoop} — everything that differs between agents, so the loop
 * itself differs not at all. The Manager streams tokens to a user and offers
 * {@code delegate}; a worker streams nothing and is cancellable. Same loop.
 */
public record LoopSpec(
        String agent,
        String conversationId,
        /** The message or task driving this turn — what the hooks see. */
        String text,
        /** Prior turns, ending with the user/task message. The loop prepends the system prompt. */
        List<ChatMessage> history,
        /** Orchestrator-implemented tools, offered alongside the agent's pool skills. */
        List<BuiltinTool> builtins,
        Consumer<String> onToken,
        /** Checked at every step boundary and on every streamed token (§3.5). */
        BooleanSupplier cancelled,
        /** Narrates the run without steering it — how a worker reports staged progress. */
        LoopObserver observer,
        /** Vets the final answer; may send the model back once. Non-streaming agents only. */
        AnswerGate answerGate) {

    public LoopSpec {
        history = history == null ? List.of() : List.copyOf(history);
        builtins = builtins == null ? List.of() : List.copyOf(builtins);
        onToken = onToken == null ? token -> { } : onToken;
        cancelled = cancelled == null ? () -> false : cancelled;
        observer = observer == null ? LoopObserver.NONE : observer;
        answerGate = answerGate == null ? AnswerGate.ACCEPT : answerGate;
    }

    public static Builder forAgent(String agent) {
        return new Builder(agent);
    }

    public static final class Builder {
        private final String agent;
        private String conversationId;
        private String text = "";
        private List<ChatMessage> history = List.of();
        private List<BuiltinTool> builtins = List.of();
        private Consumer<String> onToken;
        private BooleanSupplier cancelled;
        private LoopObserver observer;
        private AnswerGate answerGate;

        private Builder(String agent) {
            this.agent = agent;
        }

        public Builder observedBy(LoopObserver observer) {
            this.observer = observer;
            return this;
        }

        public Builder answerGate(AnswerGate answerGate) {
            this.answerGate = answerGate;
            return this;
        }

        public Builder conversation(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder history(List<ChatMessage> history) {
            this.history = history;
            return this;
        }

        public Builder builtins(List<BuiltinTool> builtins) {
            this.builtins = builtins;
            return this;
        }

        public Builder onToken(Consumer<String> onToken) {
            this.onToken = onToken;
            return this;
        }

        public Builder cancelledWhen(BooleanSupplier cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public LoopSpec build() {
            return new LoopSpec(agent, conversationId, text, history, builtins, onToken, cancelled,
                    observer, answerGate);
        }
    }
}
