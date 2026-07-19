package com.soul.orchestrator.ws;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server→client WebSocket envelope (SPEC §5.2). {@code type} is one of token,
 * agent.status, delegation, tool.call, tool.result, task.done, error.
 */
public record WsEvent(String type, String conversationId, String agent, Map<String, Object> payload) {

    public static WsEvent token(String conversationId, String agent, String messageId, String token) {
        return new WsEvent("token", conversationId, agent, Map.of("messageId", messageId, "token", token));
    }

    public static WsEvent status(String conversationId, String agent, String status, String task) {
        return new WsEvent("agent.status", conversationId, agent,
                task == null ? Map.of("status", status) : Map.of("status", status, "task", task));
    }

    public static WsEvent toolCall(String conversationId, String agent, String tool, String args) {
        return new WsEvent("tool.call", conversationId, agent, Map.of("tool", tool, "args", args));
    }

    public static WsEvent toolResult(String conversationId, String agent, String tool, String summary) {
        return new WsEvent("tool.result", conversationId, agent, Map.of("tool", tool, "summary", summary));
    }

    public static WsEvent taskDone(String conversationId, String agent, String messageId, String text) {
        return new WsEvent("task.done", conversationId, agent, Map.of("messageId", messageId, "text", text));
    }

    /**
     * One agent handed work to another. {@code id} is the command id, so the console can
     * pair this with its {@link #delegationResult} — and tell a retry (attempt 2) apart
     * from the original.
     */
    public static WsEvent delegation(String conversationId, String from, String to, String task,
            String id, int attempt) {
        return new WsEvent("delegation", conversationId, from,
                Map.of("id", id, "from", from, "to", to, "task", task, "attempt", attempt));
    }

    /**
     * How a delegation ended, with the evidence behind it — this is what lets the answer
     * show "94% · 3 sources" rather than asking the user to take SOUL's word for it (§7.4).
     */
    public static WsEvent delegationResult(String conversationId, String agent, String id, String status,
            Double confidence, List<Map<String, Object>> sources) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("status", status);
        if (confidence != null) {
            payload.put("confidence", confidence);
        }
        if (sources != null) {
            payload.put("sources", sources);
        }
        return new WsEvent("delegation.result", conversationId, agent, payload);
    }

    public static WsEvent error(String conversationId, String agent, String message) {
        return new WsEvent("error", conversationId, agent, Map.of("message", message));
    }
}
