package com.soul.orchestrator.protocol;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A fact — "this happened" (docs/researcher-agent.md §3.2). Past tense, immutable,
 * broadcast to every subscriber.
 *
 * <p>The five {@code task.*} types are deliberately worker-agnostic: researcher today,
 * calendar/email/browser tomorrow all emit the same lifecycle, and domain specifics
 * travel in {@link #payload()}. That is what lets the Manager's await and the WS bridge
 * be written once and never touched again when a new worker joins.
 */
public record AgentEvent(
        UUID id,
        UUID commandId,
        /** The conversation this event belongs to — what the WS bridge routes on (§6). */
        String conversationId,
        String type,
        String agent,
        Map<String, Object> payload,
        Instant occurredAt) {

    public static final String STARTED = "task.started";
    public static final String PROGRESS = "task.progress";
    public static final String COMPLETED = "task.completed";
    public static final String FAILED = "task.failed";
    public static final String CANCELLED = "task.cancelled";

    public AgentEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    private static AgentEvent of(AgentCommand command, String type, String agent, Map<String, Object> payload) {
        return new AgentEvent(UUID.randomUUID(), command.id(), command.conversationId(),
                type, agent, payload, Instant.now());
    }

    public static AgentEvent started(AgentCommand command, String agent) {
        return of(command, STARTED, agent, Map.of());
    }

    /** Staged progress (§3.2c): {@code stage} is the machine key, {@code label} the ready-to-render line. */
    public static AgentEvent progress(AgentCommand command, String agent, String stage, String label) {
        return progress(command, agent, stage, label, null, null);
    }

    public static AgentEvent progress(AgentCommand command, String agent, String stage, String label,
            Integer step, Integer total) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("label", label);
        if (step != null) {
            payload.put("step", step);
        }
        if (total != null) {
            payload.put("total", total);
        }
        return of(command, PROGRESS, agent, payload);
    }

    public static AgentEvent completed(AgentCommand command, String agent, TaskResult result) {
        return of(command, COMPLETED, agent, result.toPayload());
    }

    public static AgentEvent failed(AgentCommand command, String agent, String reason) {
        return of(command, FAILED, agent, Map.of("reason", reason == null ? "unknown" : reason));
    }

    public static AgentEvent cancelled(AgentCommand command, String agent) {
        return of(command, CANCELLED, agent, Map.of());
    }

    /** Terminal events resolve a pending delegation; progress/started do not. */
    public boolean isTerminal() {
        return COMPLETED.equals(type) || FAILED.equals(type) || CANCELLED.equals(type);
    }

    /** The result of a {@code task.completed} event, else empty. */
    public TaskResult result() {
        return COMPLETED.equals(type) ? TaskResult.fromPayload(payload) : null;
    }

    /** The reason of a {@code task.failed} event, else null. */
    public String reason() {
        Object value = payload.get("reason");
        return value == null ? null : value.toString();
    }
}
