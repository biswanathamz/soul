package com.soul.orchestrator.ws;

/**
 * Where the Manager emits live events. Decouples the agentic loop from the WebSocket
 * transport, so the loop can be tested with a capturing sink.
 */
public interface EventSink {
    void emit(WsEvent event);
}
