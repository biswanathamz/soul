package com.soul.orchestrator.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An imperative — "do this" (docs/researcher-agent.md §3.1). Directed at exactly one
 * agent; its {@link #id()} is the correlation key every resulting {@link AgentEvent}
 * carries. Commands name work to do; events state facts that happened — two separate
 * models on two separate buses, never mixed.
 */
public record AgentCommand(
        UUID id,
        String type,
        String issuedBy,
        String target,
        String conversationId,
        Map<String, Object> payload,
        Instant issuedAt) {

    /** Do a unit of work. Payload: {@code {task, capability, …}}. */
    public static final String TASK = "task";
    /** Stop a task already in flight. Payload: {@code {commandId}}. */
    public static final String CANCEL = "cancel";

    public AgentCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        issuedAt = issuedAt == null ? Instant.now() : issuedAt;
    }

    public static AgentCommand task(String issuedBy, String target, String conversationId,
            Map<String, Object> payload) {
        return new AgentCommand(UUID.randomUUID(), TASK, issuedBy, target, conversationId, payload, Instant.now());
    }

    /** Stop {@code taskCommandId}. One mechanism, three triggers: stop button, voice, timeout (§3.5). */
    public static AgentCommand cancel(String issuedBy, String target, String conversationId, UUID taskCommandId) {
        return new AgentCommand(UUID.randomUUID(), CANCEL, issuedBy, target, conversationId,
                Map.of("commandId", Objects.requireNonNull(taskCommandId, "taskCommandId")), Instant.now());
    }

    public boolean isTask() {
        return TASK.equals(type);
    }

    public boolean isCancel() {
        return CANCEL.equals(type);
    }

    /** The task command this cancel targets — cancel commands only, else null. */
    public UUID cancelTarget() {
        Object value = payload.get("commandId");
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    /** The capability that was invoked, as resolved by the registry — task commands only. */
    public String capability() {
        Object value = payload.get("capability");
        return value == null ? null : value.toString();
    }
}
