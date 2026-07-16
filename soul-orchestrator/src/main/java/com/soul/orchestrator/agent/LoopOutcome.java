package com.soul.orchestrator.agent;

/**
 * How an {@link AgentLoop} run ended. The loop reports; the caller decides what it means
 * — the Manager turns these into user-facing replies, a worker into {@code task.*} events.
 */
public record LoopOutcome(Status status, String text) {

    public enum Status {
        /** The model produced a final answer — {@link #text()} holds it. */
        ANSWERED,
        /** The step budget ran out before the model settled on an answer. */
        EXHAUSTED,
        /** Stopped cooperatively at a step boundary or mid-generation. */
        CANCELLED,
        /** The model could not be reached or errored — {@link #text()} holds the reason. */
        FAILED
    }

    public static LoopOutcome answered(String text) {
        return new LoopOutcome(Status.ANSWERED, text);
    }

    public static LoopOutcome exhausted() {
        return new LoopOutcome(Status.EXHAUSTED, null);
    }

    public static LoopOutcome cancelled() {
        return new LoopOutcome(Status.CANCELLED, null);
    }

    public static LoopOutcome failed(String reason) {
        return new LoopOutcome(Status.FAILED, reason);
    }
}
