package com.soul.orchestrator.conversation;

import java.time.Instant;

/** A persisted chat message (matches the UI's Message DTO, SPEC §5). */
public record StoredMessage(String id, String role, String text, String createdAt) {

    public static StoredMessage of(String id, String role, String text) {
        return new StoredMessage(id, role, text, Instant.now().toString());
    }
}
