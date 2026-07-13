package com.soul.orchestrator.ws;

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

    public static WsEvent error(String conversationId, String agent, String message) {
        return new WsEvent("error", conversationId, agent, Map.of("message", message));
    }
}
