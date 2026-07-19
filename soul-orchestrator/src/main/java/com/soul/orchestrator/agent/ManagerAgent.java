package com.soul.orchestrator.agent;

import com.soul.orchestrator.conversation.ConversationStore;
import com.soul.orchestrator.conversation.StoredMessage;
import com.soul.orchestrator.hooks.HookDispatcher;
import com.soul.orchestrator.hooks.HookOutcome;
import com.soul.orchestrator.ollama.ChatMessage;
import com.soul.orchestrator.ws.EventSink;
import com.soul.orchestrator.ws.WsEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Manager ("SOUL") — the agent the user talks to. It is {@link AgentLoop} plus the
 * things only a user-facing agent has: the conversation store, token streaming to a
 * message id, and the {@code user_message_received} / {@code before_respond} gates
 * (docs/researcher-agent.md §4.1).
 *
 * <p>Workers are the same loop with command/event plumbing instead. Sub-agents never talk
 * to the user; everything the user hears comes from here.
 */
@Component
public class ManagerAgent {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);
    private static final String AGENT = "super";

    private final CapabilityResolver resolver;
    private final HookDispatcher hooks;
    private final AgentLoop loop;
    private final DelegateTool delegateTool;
    private final ConversationStore conversations;
    private final EventSink events;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "manager-agent");
        t.setDaemon(true);
        return t;
    });

    public ManagerAgent(CapabilityResolver resolver, HookDispatcher hooks, AgentLoop loop,
            DelegateTool delegateTool, ConversationStore conversations, EventSink events) {
        this.resolver = resolver;
        this.hooks = hooks;
        this.loop = loop;
        this.delegateTool = delegateTool;
        this.conversations = conversations;
        this.events = events;
    }

    /** Run a turn off the request thread; the reply streams over the WebSocket. */
    public void submit(String conversationId, String assistantMessageId, String userText) {
        executor.submit(() -> {
            try {
                handle(conversationId, assistantMessageId, userText);
            } catch (Exception e) {
                log.error("manager turn failed", e);
                events.emit(WsEvent.error(conversationId, AGENT, e.getMessage()));
                events.emit(WsEvent.status(conversationId, AGENT, "idle", null));
            }
        });
    }

    /** Synchronous turn — used directly by tests. */
    public void handle(String conversationId, String assistantMessageId, String userText) {
        AgentCapabilities caps = resolver.resolve(AGENT);
        Map<String, Object> context = Map.of("conversationId", conversationId, "agent", AGENT);

        events.emit(WsEvent.status(conversationId, AGENT, "thinking", "Understanding request"));

        // user_message_received hooks may reject or rewrite the message.
        HookOutcome received = hooks.dispatch("user_message_received", caps, Map.of("text", userText), context);
        if (received.blocked()) {
            fail(conversationId, assistantMessageId, received.reason());
            return;
        }
        String message = received.messageRewrite() != null ? received.messageRewrite() : userText;

        LoopOutcome outcome = loop.run(LoopSpec.forAgent(AGENT)
                .conversation(conversationId)
                .text(message)
                .history(priorHistory(conversationId))
                // The one delegation tool — absent entirely when no worker has registered.
                .builtins(delegateTool.forConversation(conversationId).map(List::of).orElseGet(List::of))
                .onToken(token -> events.emit(WsEvent.token(conversationId, AGENT, assistantMessageId, token)))
                .build());

        switch (outcome.status()) {
            case ANSWERED -> finish(conversationId, assistantMessageId, caps, context, outcome.text());
            case EXHAUSTED -> finish(conversationId, assistantMessageId, caps, context,
                    "I wasn't able to finish that within my step budget. Could you rephrase?");
            case CANCELLED -> finish(conversationId, assistantMessageId, caps, context, "Alright, stopped.");
            case FAILED -> fail(conversationId, assistantMessageId, outcome.text());
        }
    }

    private void finish(String conversationId, String messageId, AgentCapabilities caps,
            Map<String, Object> context, String text) {
        HookOutcome before = hooks.dispatch("before_respond", caps, Map.of("text", text), context);
        if (before.blocked()) {
            fail(conversationId, messageId, before.reason());
            return;
        }
        String finalText = before.messageRewrite() != null ? before.messageRewrite() : text;
        conversations.append(conversationId, "assistant", finalText);
        events.emit(WsEvent.taskDone(conversationId, AGENT, messageId, finalText));
        events.emit(WsEvent.status(conversationId, AGENT, "idle", null));
    }

    private void fail(String conversationId, String messageId, String reason) {
        events.emit(WsEvent.error(conversationId, AGENT, reason));
        events.emit(WsEvent.status(conversationId, AGENT, "idle", null));
    }

    private List<ChatMessage> priorHistory(String conversationId) {
        List<ChatMessage> out = new ArrayList<>();
        for (StoredMessage m : conversations.history(conversationId).orElse(List.of())) {
            out.add("assistant".equals(m.role()) ? ChatMessage.assistant(m.text()) : ChatMessage.user(m.text()));
        }
        return out;
    }
}
