package com.soul.orchestrator.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The {@code /ws/stream} endpoint and the {@link EventSink} in one: holds the open
 * sessions and broadcasts each event as JSON. Single local user, so events fan out to
 * all sessions (SPEC §5.2).
 */
@Component
public class StreamWebSocketHandler extends TextWebSocketHandler implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(StreamWebSocketHandler.class);

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("ws connected: {} ({} open)", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.debug("ws closed: {} ({} open)", session.getId(), sessions.size());
    }

    @Override
    public void emit(WsEvent event) {
        TextMessage frame;
        try {
            frame = new TextMessage(mapper.writeValueAsString(event));
        } catch (IOException e) {
            log.warn("cannot serialize ws event: {}", e.getMessage());
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(frame);
                } catch (IOException e) {
                    log.debug("ws send to {} failed: {}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
