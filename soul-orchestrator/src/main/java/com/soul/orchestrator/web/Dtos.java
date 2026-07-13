package com.soul.orchestrator.web;

import com.soul.orchestrator.conversation.StoredMessage;
import java.util.List;

/** Request/response DTOs for the REST surface (SPEC §5.1). */
public final class Dtos {

    private Dtos() {}

    public record ChatRequest(String conversationId, String text) {}

    public record ChatResponse(String conversationId, String messageId) {}

    public record AgentDto(
            String role,
            String model,
            String status,
            String description,
            List<String> skills,
            List<String> hooks) {}

    public record RebindRequest(String model) {}

    public record ModelDto(String name) {}

    public record ConversationDto(String id, List<StoredMessage> messages) {}
}
