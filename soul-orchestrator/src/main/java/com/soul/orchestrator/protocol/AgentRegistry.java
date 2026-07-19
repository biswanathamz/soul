package com.soul.orchestrator.protocol;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Answers exactly one question — <i>"who supports this capability?"</i>
 * (docs/researcher-agent.md §3.4). Callers never bind to agent names, so agents are
 * swappable providers behind their capabilities.
 *
 * <p>This registry is the source of truth for the {@code delegate} tool: its
 * {@code capability} enum and description are generated from it. Adding a worker =
 * declaring its capabilities. No new tools, no Manager code, no prompt edits.
 */
public interface AgentRegistry {

    /** Advertise an agent's contract and wire its worker to the {@link CommandBus}. */
    void register(AgentDescriptor descriptor, Consumer<AgentCommand> worker);

    /** THE routing question. Empty when nothing provides {@code capability}. */
    Optional<AgentDescriptor> whoSupports(String capability);

    /** The union of every registered capability — feeds the delegate tool's enum. */
    Set<String> capabilities();

    List<AgentDescriptor> available();
}
