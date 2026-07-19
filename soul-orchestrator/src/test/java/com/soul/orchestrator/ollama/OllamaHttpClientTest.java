package com.soul.orchestrator.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soul.orchestrator.config.SoulProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the real HTTP client against a tiny stub Ollama, asserting what goes on the wire. */
class OllamaHttpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void startStubOllama() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // One NDJSON line: a token, then done.
            byte[] out = "{\"message\":{\"content\":\"hi\"},\"done\":true}\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OllamaHttpClient clientWithKeepAlive(String keepAlive) {
        SoulProperties props = new SoulProperties();
        props.getOllama().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getOllama().setKeepAlive(keepAlive);
        return new OllamaHttpClient(props);
    }

    @Test
    void sendsKeepAliveOnEveryChatRequestSoTheModelStaysWarm() throws Exception {
        List<String> tokens = new ArrayList<>();

        ChatTurn turn = clientWithKeepAlive("30m")
                .chat("llama3.2:3b", List.of(ChatMessage.user("hi")), List.of(), tokens::add);

        JsonNode body = mapper.readTree(capturedBody.get());
        assertThat(body.path("keep_alive").asText()).isEqualTo("30m");
        assertThat(body.path("model").asText()).isEqualTo("llama3.2:3b");
        assertThat(tokens).containsExactly("hi");
        assertThat(turn.content()).isEqualTo("hi");
    }

    @Test
    void omitsKeepAliveWhenBlankSoOllamaKeepsItsOwnDefault() throws Exception {
        clientWithKeepAlive("").chat("llama3.2:3b", List.of(ChatMessage.user("hi")), List.of(), t -> { });

        assertThat(mapper.readTree(capturedBody.get()).has("keep_alive")).isFalse();
    }
}
