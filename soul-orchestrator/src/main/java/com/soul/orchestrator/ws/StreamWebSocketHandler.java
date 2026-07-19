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
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The {@code /ws/stream} endpoint and the {@link EventSink} in one: holds the open
 * sessions and broadcasts each event as JSON. Single local user, so events fan out to
 * all sessions (SPEC §5.2).
 */
@Component
public class StreamWebSocketHandler extends TextWebSocketHandler implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(StreamWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // A WebSocketSession is NOT safe for concurrent sends, and SOUL now has more than
        // one agent emitting: the Manager streams tokens from its thread while a worker
        // narrates progress from its own executor. Unguarded, the two interleave mid-frame
        // and Tomcat throws "remote endpoint was in state [TEXT_PARTIAL_WRITING]" — which
        // is exactly what the first two-agent run did. The decorator serializes sends and
        // buffers a slow client instead of corrupting the stream.
        sessions.add(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES));
        log.debug("ws connected: {} ({} open)", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Sessions are stored wrapped, so match on id rather than identity.
        sessions.removeIf(open -> open.getId().equals(session.getId()));
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
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(frame);
            } catch (Exception e) {
                // Never propagate: emit() is called from inside agent loops, and a browser
                // that went away must not take an agent's turn down with it.
                log.debug("ws send to {} failed: {}", session.getId(), e.toString());
            }
        }
    }
}
