package com.soul.orchestrator.protocol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The cancellation flags workers check (docs/researcher-agent.md §3.5). Cancellation is
 * <b>cooperative</b>: a cancel command sets the flag here, and the agent loop tests it at
 * every step boundary — before each model call, before each skill run — so a step already
 * in progress winds down rather than being killed mid-flight.
 *
 * <p>Shared by every worker, so no worker reimplements it.
 */
@Component
public class CancellationRegistry {

    private static final Boolean ACTIVE = Boolean.FALSE;
    private static final Boolean CANCELLED = Boolean.TRUE;

    private final Map<UUID, Boolean> flags = new ConcurrentHashMap<>();

    /**
     * Mark a task as running. Deliberately {@code putIfAbsent}: a cancel that races ahead
     * of the executor picking the task up has already written CANCELLED, and must not be
     * overwritten back to ACTIVE — otherwise a fast "stop" would be silently swallowed.
     */
    public void begin(UUID commandId) {
        flags.putIfAbsent(commandId, ACTIVE);
    }

    /**
     * Request cancellation. Unconditional, to keep the race above safe; the caller side
     * ({@link PendingDelegations#cancelConversation}) only ever cancels a delegation it
     * knows is in flight, so a cancel for an already-finished command — a harmless no-op
     * behaviourally — stays vanishingly rare.
     */
    public void cancel(UUID commandId) {
        flags.put(commandId, CANCELLED);
    }

    public boolean isCancelled(UUID commandId) {
        return CANCELLED.equals(flags.get(commandId));
    }

    /** Drop the flag once a task is done — workers call this in a {@code finally}. */
    public void end(UUID commandId) {
        flags.remove(commandId);
    }
}
