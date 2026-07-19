package com.soul.orchestrator.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** The transport two agents now share — and must not corrupt or be killed by. */
class StreamWebSocketHandlerTest {

    private final StreamWebSocketHandler handler = new StreamWebSocketHandler();

    /** Fails if two threads are ever inside sendMessage at once — what Tomcat really does. */
    private static class RecordingSession implements WebSocketSession {
        private final AtomicInteger inFlight = new AtomicInteger();
        final List<String> sent = new ArrayList<>();
        volatile boolean overlapped;
        volatile boolean explode;

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (explode) {
                throw new IllegalStateException("remote endpoint was in state [TEXT_PARTIAL_WRITING]");
            }
            if (inFlight.incrementAndGet() > 1) {
                overlapped = true;
            }
            try {
                Thread.sleep(1); // widen the window a real socket write would have
                synchronized (sent) {
                    sent.add(message.getPayload().toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public String getId() {
            return "test-session";
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        // --- unused WebSocketSession surface ---
        @Override public URI getUri() { return URI.create("ws://localhost/ws/stream"); }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return Map.of(); }
        @Override public Principal getPrincipal() { return null; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int limit) { }
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int limit) { }
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void close() { }
        @Override public void close(CloseStatus status) { }
    }

    @Test
    void twoAgentsEmittingAtOnceNeverInterleaveOnTheWire() throws Exception {
        // The Manager streams tokens from its thread while the Researcher narrates progress
        // from its own executor. Unguarded this corrupts the socket mid-frame — the first
        // live two-agent run died on exactly that.
        RecordingSession session = new RecordingSession();
        handler.afterConnectionEstablished(session);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        for (String agent : List.of("super", "researcher")) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < 60; i++) {
                    handler.emit(WsEvent.status("conv-1", agent, "working", agent + " step " + i));
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, SECONDS)).isTrue();

        assertThat(session.overlapped).as("two threads inside sendMessage at once").isFalse();
        assertThat(session.sent).hasSize(120); // and nothing was dropped
    }

    @Test
    void aBrokenSessionNeverTakesAnAgentsTurnDownWithIt() {
        // emit() runs inside the agent loop: an exception here escaped and killed the
        // Manager's whole turn.
        RecordingSession session = new RecordingSession();
        session.explode = true;
        handler.afterConnectionEstablished(session);

        assertThatCode(() -> handler.emit(WsEvent.status("conv-1", "super", "working", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void aClosedSessionIsForgotten() {
        RecordingSession session = new RecordingSession();
        handler.afterConnectionEstablished(session);

        // Sessions are stored wrapped for thread-safety, so removal must match on id.
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.emit(WsEvent.status("conv-1", "super", "working", "x"));

        assertThat(session.sent).isEmpty();
    }
}
