package com.soul.orchestrator.protocol;

import java.util.function.Consumer;

/**
 * Carries imperatives to exactly one target (docs/researcher-agent.md §3.3). In-process
 * today; the protocol is transport-agnostic, so a queue could replace the implementation
 * without touching a single agent.
 */
public interface CommandBus {

    /** Route {@code command} to its target. Throws if no worker is registered for it. */
    void send(AgentCommand command);

    /** Wire a worker to receive commands addressed to {@code target}. */
    void register(String target, Consumer<AgentCommand> worker);
}
