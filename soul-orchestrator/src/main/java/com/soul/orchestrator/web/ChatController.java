package com.soul.orchestrator.web;

import com.soul.orchestrator.agent.ManagerAgent;
import com.soul.orchestrator.conversation.ConversationStore;
import com.soul.orchestrator.conversation.StoredMessage;
import com.soul.orchestrator.web.Dtos.ChatRequest;
import com.soul.orchestrator.web.Dtos.ChatResponse;
import com.soul.orchestrator.web.Dtos.ConversationDto;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chat entry point (SPEC §5.1). The POST kicks off the Manager and returns immediately;
 * the reply streams over the WebSocket. History is fetchable for reload/rehydrate.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ConversationStore conversations;
    private final ManagerAgent manager;

    public ChatController(ConversationStore conversations, ManagerAgent manager) {
        this.conversations = conversations;
        this.manager = manager;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        String conversationId = conversations.ensure(request.conversationId());
        conversations.append(conversationId, "user", request.text());
        String assistantMessageId = UUID.randomUUID().toString();
        manager.submit(conversationId, assistantMessageId, request.text().strip());
        return new ChatResponse(conversationId, assistantMessageId);
    }

    @GetMapping("/conversations/{id}")
    public ConversationDto conversation(@PathVariable String id) {
        java.util.List<StoredMessage> messages = conversations.history(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found"));
        return new ConversationDto(id, messages);
    }
}
