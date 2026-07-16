package com.soul.orchestrator.ws;

import com.soul.orchestrator.protocol.AgentEvent;
import com.soul.orchestrator.protocol.EventBus;
import org.springframework.stereotype.Component;

/**
 * Turns protocol events into UI events (docs/researcher-agent.md §6). One of the two
 * standing subscribers on the event bus.
 *
 * <p>Written once against the generic {@code task.*} lifecycle, so a new worker needs no
 * change here — and because a progress event already carries a ready-to-render
 * {@code label}, the whole staged narration reaches the console as plain
 * {@code agent.status} with <b>zero new console event handling</b>.
 */
@Component
public class ProtocolWsBridge {

    private final EventSink sink;

    public ProtocolWsBridge(EventBus events, EventSink sink) {
        this.sink = sink;
        events.subscribe(this::onEvent);
    }

    private void onEvent(AgentEvent event) {
        String conversationId = event.conversationId();
        String agent = event.agent();
        switch (event.type()) {
            case AgentEvent.STARTED -> sink.emit(WsEvent.status(conversationId, agent, "working", null));
            case AgentEvent.PROGRESS -> sink.emit(
                    WsEvent.status(conversationId, agent, "working", label(event)));
            case AgentEvent.COMPLETED, AgentEvent.FAILED, AgentEvent.CANCELLED ->
                    sink.emit(WsEvent.status(conversationId, agent, "idle", null));
            default -> {
                // Unknown types are ignored for forward compatibility.
            }
        }
    }

    private static String label(AgentEvent event) {
        Object label = event.payload().get("label");
        return label == null ? null : label.toString();
    }
}
