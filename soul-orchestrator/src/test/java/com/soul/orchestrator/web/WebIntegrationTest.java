package com.soul.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.soul.orchestrator.web.Dtos.AgentDto;
import com.soul.orchestrator.web.Dtos.ChatRequest;
import com.soul.orchestrator.web.Dtos.ChatResponse;
import com.soul.orchestrator.web.Dtos.ConversationDto;
import com.soul.orchestrator.web.Dtos.ModelDto;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** Boots the real orchestrator web app with the stub model and drives the REST surface. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub-ollama")
class WebIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void chatKicksOffTheManagerAndPersistsAReply() {
        ChatResponse res = rest.postForObject("/api/v1/chat",
                new ChatRequest(null, "What is the current time?"), ChatResponse.class);
        assertThat(res.conversationId()).isNotBlank();
        assertThat(res.messageId()).isNotBlank();

        // The manager runs async; its answer should land in the conversation shortly.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ConversationDto convo =
                    rest.getForObject("/api/v1/conversations/" + res.conversationId(), ConversationDto.class);
            assertThat(convo.messages())
                    .anyMatch(m -> m.role().equals("assistant") && m.text().contains("current time"));
        });
    }

    @Test
    void rejectsEmptyMessage() {
        ResponseEntity<String> res =
                rest.postForEntity("/api/v1/chat", new ChatRequest(null, "   "), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsTheManagerAgentWithItsCapabilities() {
        AgentDto[] agents = rest.getForObject("/api/v1/agents", AgentDto[].class);
        assertThat(agents).hasSize(1);
        AgentDto manager = agents[0];
        assertThat(manager.role()).isEqualTo("super");
        assertThat(manager.model()).isEqualTo("llama3.1:8b");
        assertThat(manager.skills()).contains("echo", "current-time", "persona");
        assertThat(manager.hooks()).contains("block-secrets");
    }

    @Test
    void listsModelsFromOllama() {
        ModelDto[] models = rest.getForObject("/api/v1/models", ModelDto[].class);
        assertThat(models).extracting(ModelDto::name).contains("llama3.1:8b");
    }

    @Test
    void rebindsTheManagerModel() {
        AgentDto updated = rest.exchange("/api/v1/agents/super/model",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of("model", "qwen2.5:0.5b")),
                AgentDto.class).getBody();
        assertThat(updated.model()).isEqualTo("qwen2.5:0.5b");
        // Restore so this test can't perturb the shared context for others.
        rest.exchange("/api/v1/agents/super/model", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of("model", "llama3.1:8b")), AgentDto.class);
    }

    @Test
    void healthIsUp() {
        String health = rest.getForObject("/actuator/health", String.class);
        assertThat(health).contains("UP");
    }
}
