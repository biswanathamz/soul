package com.soul.orchestrator.protocol;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synchronous broadcast to every subscriber (docs/researcher-agent.md §3.3). Publishing
 * happens on the worker's own thread, so listeners must stay cheap — both standing
 * subscribers (pending-delegation completion, the WS bridge) are.
 */
@Component
public class InProcessEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void publish(AgentEvent event) {
        for (Consumer<AgentEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // One bad subscriber must never stop the others from seeing the event.
                log.error("event listener threw on {} for command {}", event.type(), event.commandId(), e);
            }
        }
    }
}
