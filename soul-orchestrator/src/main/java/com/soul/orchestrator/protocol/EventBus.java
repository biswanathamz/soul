package com.soul.orchestrator.protocol;

import java.util.function.Consumer;

/**
 * Broadcasts facts to every listener (docs/researcher-agent.md §3.3). Two standing
 * subscribers: the Manager (completes pending delegations) and the WS bridge (turns
 * protocol events into UI events).
 */
public interface EventBus {

    void publish(AgentEvent event);

    void subscribe(Consumer<AgentEvent> listener);
}
