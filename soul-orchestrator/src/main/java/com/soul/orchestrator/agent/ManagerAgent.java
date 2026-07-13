package com.soul.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.conversation.ConversationStore;
import com.soul.orchestrator.conversation.StoredMessage;
import com.soul.orchestrator.hooks.HookDispatcher;
import com.soul.orchestrator.hooks.HookOutcome;
import com.soul.orchestrator.ollama.ChatMessage;
import com.soul.orchestrator.ollama.ChatTurn;
import com.soul.orchestrator.ollama.OllamaClient;
import com.soul.orchestrator.ollama.OllamaException;
import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.ollama.ToolSpec;
import com.soul.orchestrator.runtime.Runner;
import com.soul.orchestrator.runtime.RunnerResult;
import com.soul.orchestrator.skills.SkillManifest;
import com.soul.orchestrator.ws.EventSink;
import com.soul.orchestrator.ws.WsEvent;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Manager ("SOUL") — the agent the user talks to. Runs the bounded agentic loop
 * (docs/manager-agent.md §2.3): hooks → model → skills-as-tools → hooks → …, streaming
 * every step to the UI over the WebSocket contract (SPEC §5.2). This phase builds the
 * one agent; the machinery is agent-generic.
 */
@Component
public class ManagerAgent {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);
    private static final String AGENT = "super";

    private final SoulProperties props;
    private final CapabilityResolver resolver;
    private final HookDispatcher hooks;
    private final Runner runner;
    private final OllamaClient ollama;
    private final ConversationStore conversations;
    private final EventSink events;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "manager-agent");
        t.setDaemon(true);
        return t;
    });

    public ManagerAgent(SoulProperties props, CapabilityResolver resolver, HookDispatcher hooks,
            Runner runner, OllamaClient ollama, ConversationStore conversations, EventSink events) {
        this.props = props;
        this.resolver = resolver;
        this.hooks = hooks;
        this.runner = runner;
        this.ollama = ollama;
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
        SoulProperties.Agent cfg = props.getAgents().get(AGENT);
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

        // before_model hooks inject system context (e.g. the current time).
        HookOutcome beforeModel = hooks.dispatch("before_model", caps, Map.of("text", message), context);
        String system = buildSystemPrompt(caps, beforeModel.appendSystem());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.addAll(priorHistory(conversationId));

        List<ToolSpec> tools = caps.scriptSkills().stream().map(ToolSpec::fromSkill).toList();
        int maxSteps = cfg == null ? 6 : cfg.getMaxSteps();
        String model = cfg == null ? "llama3.1:8b" : cfg.getModel();

        for (int step = 0; step < maxSteps; step++) {
            ChatTurn turn;
            try {
                turn = ollama.chat(model, messages, tools,
                        token -> events.emit(WsEvent.token(conversationId, AGENT, assistantMessageId, token)));
            } catch (OllamaException e) {
                fail(conversationId, assistantMessageId, e.getMessage());
                return;
            }

            if (!turn.hasToolCalls()) {
                finish(conversationId, assistantMessageId, caps, context, turn.content());
                return;
            }

            messages.add(ChatMessage.assistantToolCalls(turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                String result = runSkill(conversationId, caps, context, call);
                messages.add(ChatMessage.toolResult(result));
            }
        }

        // Loop bound hit without a final answer.
        finish(conversationId, assistantMessageId, caps, context,
                "I wasn't able to finish that within my step budget. Could you rephrase?");
    }

    private String runSkill(String conversationId, AgentCapabilities caps, Map<String, Object> context, ToolCall call) {
        events.emit(WsEvent.status(conversationId, AGENT, "working", "running " + call.name()));

        // before_skill hooks — a blocking safety gate (e.g. block-secrets) may veto.
        HookOutcome before = hooks.dispatch("before_skill", caps,
                Map.of("skill", call.name(), "input", call.arguments()), context);
        if (before.blocked()) {
            events.emit(WsEvent.error(conversationId, AGENT, "Skill '" + call.name() + "' blocked: " + before.reason()));
            return "Refused: " + before.reason();
        }

        SkillManifest skill = caps.skills().stream()
                .filter(s -> s.name().equals(call.name()) && s.type() == SkillManifest.Type.SCRIPT)
                .findFirst().orElse(null);
        if (skill == null) {
            return "Error: no such skill '" + call.name() + "'";
        }

        events.emit(WsEvent.toolCall(conversationId, AGENT, call.name(), argsToString(call.arguments())));
        Map<String, Object> request = Map.of("skill", call.name(), "input", call.arguments(), "context", context);
        RunnerResult r = runner.run(skill.entrypointPath(), request, Duration.ofSeconds(skill.timeoutSeconds()));

        String output;
        JsonNode json = r.json();
        if (r.ok() && json != null && json.path("ok").asBoolean(false)) {
            output = json.path("output").asText("");
        } else {
            output = "Error: " + (r.stderr().isBlank() ? "skill failed" : r.stderr().trim());
        }

        HookOutcome after = hooks.dispatch("after_skill", caps,
                Map.of("skill", call.name(), "output", output), context);
        if (after.outputRewrite() != null) {
            output = after.outputRewrite();
        }
        String summary = json != null && json.hasNonNull("display") ? json.get("display").asText() : "ok";
        events.emit(WsEvent.toolResult(conversationId, AGENT, call.name(), summary));
        return output;
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

    private String buildSystemPrompt(AgentCapabilities caps, List<String> appendSystem) {
        StringBuilder sb = new StringBuilder();
        for (SkillManifest prompt : caps.promptSkills()) {
            if (prompt.promptAlways()) {
                try {
                    sb.append(Files.readString(prompt.promptPath()).strip()).append("\n\n");
                } catch (Exception e) {
                    log.warn("cannot read prompt skill {}: {}", prompt.name(), e.getMessage());
                }
            }
        }
        for (String extra : appendSystem) {
            sb.append(extra).append("\n");
        }
        return sb.toString().strip();
    }

    private List<ChatMessage> priorHistory(String conversationId) {
        List<ChatMessage> out = new ArrayList<>();
        for (StoredMessage m : conversations.history(conversationId).orElse(List.of())) {
            out.add("assistant".equals(m.role()) ? ChatMessage.assistant(m.text()) : ChatMessage.user(m.text()));
        }
        return out;
    }

    private String argsToString(Map<String, Object> args) {
        try {
            return args.isEmpty() ? "" : mapper.writeValueAsString(args);
        } catch (Exception e) {
            return "";
        }
    }
}
