package com.soul.orchestrator.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soul.orchestrator.config.SoulProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Real Ollama REST client — streams {@code POST /api/chat} (NDJSON) and lists models
 * via {@code GET /api/tags}. Active unless the {@code stub-ollama} profile is on.
 */
@Component
@Profile("!stub-ollama")
public class OllamaHttpClient implements OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaHttpClient.class);

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaHttpClient(SoulProperties props) {
        this.baseUrl = props.getOllama().getBaseUrl().replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(props.getOllama().getRequestTimeoutSeconds());
    }

    @Override
    public ChatTurn chat(String model, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.set("messages", encodeMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", encodeTools(tools));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        try {
            HttpResponse<java.io.InputStream> resp =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new OllamaException("Ollama /api/chat returned HTTP " + resp.statusCode());
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode chunk = mapper.readTree(line);
                    if (chunk.hasNonNull("error")) {
                        throw new OllamaException(chunk.get("error").asText());
                    }
                    JsonNode message = chunk.get("message");
                    if (message != null) {
                        JsonNode delta = message.get("content");
                        if (delta != null && !delta.asText().isEmpty()) {
                            content.append(delta.asText());
                            onToken.accept(delta.asText());
                        }
                        JsonNode calls = message.get("tool_calls");
                        if (calls != null && calls.isArray()) {
                            for (JsonNode call : calls) {
                                toolCalls.add(decodeToolCall(call));
                            }
                        }
                    }
                    if (chunk.path("done").asBoolean(false)) {
                        break;
                    }
                }
            }
        } catch (OllamaException e) {
            throw e;
        } catch (Exception e) {
            throw new OllamaException("cannot reach Ollama at " + baseUrl + ": " + e.getMessage(), e);
        }
        return new ChatTurn(content.toString(), toolCalls);
    }

    @Override
    public List<String> listModels() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            List<String> names = new ArrayList<>();
            for (JsonNode m : mapper.readTree(resp.body()).path("models")) {
                names.add(m.path("name").asText());
            }
            return names;
        } catch (Exception e) {
            log.warn("cannot list Ollama models: {}", e.getMessage());
            return List.of();
        }
    }

    private ArrayNode encodeMessages(List<ChatMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", m.role());
            node.put("content", m.content() == null ? "" : m.content());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode calls = mapper.createArrayNode();
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode fn = mapper.createObjectNode();
                    fn.put("name", c.name());
                    fn.set("arguments", mapper.valueToTree(c.arguments()));
                    calls.add(mapper.createObjectNode().set("function", fn));
                }
                node.set("tool_calls", calls);
            }
            arr.add(node);
        }
        return arr;
    }

    private ArrayNode encodeTools(List<ToolSpec> tools) {
        ArrayNode arr = mapper.createArrayNode();
        for (ToolSpec t : tools) {
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.set("parameters", mapper.valueToTree(t.parameters()));
            arr.add(mapper.createObjectNode().put("type", "function").set("function", fn));
        }
        return arr;
    }

    @SuppressWarnings("unchecked")
    private ToolCall decodeToolCall(JsonNode call) {
        JsonNode fn = call.path("function");
        String name = fn.path("name").asText();
        JsonNode args = fn.path("arguments");
        Map<String, Object> arguments = args.isObject()
                ? mapper.convertValue(args, Map.class)
                : Map.of();
        return new ToolCall(name, arguments);
    }
}
