package com.soul.orchestrator.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * In-memory conversation history (SPEC §8 — H2 persistence comes later). Thread-safe
 * so the async Manager loop and REST reads don't collide.
 */
@Component
public class ConversationStore {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StoredMessage>> conversations =
            new ConcurrentHashMap<>();

    /** Ensure a conversation exists, creating one with a fresh id when none is given. */
    public String ensure(String conversationId) {
        String id = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;
        conversations.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        return id;
    }

    public StoredMessage append(String conversationId, String role, String text) {
        StoredMessage message = StoredMessage.of(UUID.randomUUID().toString(), role, text);
        conversations.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).add(message);
        return message;
    }

    public Optional<List<StoredMessage>> history(String conversationId) {
        CopyOnWriteArrayList<StoredMessage> messages = conversations.get(conversationId);
        return Optional.ofNullable(messages).map(List::copyOf);
    }
}
