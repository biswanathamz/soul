package com.soul.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soul.orchestrator.config.SoulProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The reusable agentic core (docs/manager-agent.md §2.3, researcher-agent.md §4.1):
 * hooks → model → skills-as-tools → hooks, bounded by {@code max-steps}, streaming every
 * step to the UI under the running agent's name.
 *
 * <p>Nothing here knows about users, conversations or delegation — that is why both the
 * Manager (which adds user I/O and the {@code delegate} tool) and every worker (which
 * adds command/event plumbing) are thin wrappers over the same loop.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final String FALLBACK_MODEL = "llama3.1:8b";
    private static final int FALLBACK_MAX_STEPS = 6;

    private final SoulProperties props;
    private final CapabilityResolver resolver;
    private final HookDispatcher hooks;
    private final Runner runner;
    private final OllamaClient ollama;
    private final EventSink events;
    private final SkillEnvironment skillEnv;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentLoop(SoulProperties props, CapabilityResolver resolver, HookDispatcher hooks,
            Runner runner, OllamaClient ollama, EventSink events, SkillEnvironment skillEnv) {
        this.props = props;
        this.resolver = resolver;
        this.hooks = hooks;
        this.runner = runner;
        this.ollama = ollama;
        this.events = events;
        this.skillEnv = skillEnv;
    }

    public LoopOutcome run(LoopSpec spec) {
        String agent = spec.agent();
        SoulProperties.Agent cfg = props.getAgents().get(agent);
        AgentCapabilities caps = resolver.resolve(agent);
        Map<String, Object> context = Map.of("conversationId", spec.conversationId(), "agent", agent);

        // before_model hooks inject system context (e.g. the current time).
        HookOutcome beforeModel = hooks.dispatch("before_model", caps, Map.of("text", spec.text()), context);
        String system = buildSystemPrompt(caps, beforeModel.appendSystem());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.addAll(spec.history());

        List<ToolSpec> tools = new ArrayList<>(caps.scriptSkills().stream().map(ToolSpec::fromSkill).toList());
        spec.builtins().forEach(builtin -> tools.add(builtin.spec()));

        int maxSteps = cfg == null ? FALLBACK_MAX_STEPS : cfg.getMaxSteps();
        String model = cfg == null ? FALLBACK_MODEL : cfg.getModel();
        boolean nudged = false;

        for (int step = 0; step < maxSteps; step++) {
            if (spec.cancelled().getAsBoolean()) {
                return LoopOutcome.cancelled(); // step boundary: before each model call
            }
            spec.observer().onStep(step);
            ChatTurn turn;
            try {
                turn = ollama.chat(model, messages, tools, token -> {
                    if (spec.cancelled().getAsBoolean()) {
                        throw new LoopCancelledException(); // abort the generation in flight
                    }
                    spec.onToken().accept(token);
                });
            } catch (LoopCancelledException e) {
                return LoopOutcome.cancelled();
            } catch (OllamaException e) {
                return LoopOutcome.failed(e.getMessage());
            }

            if (!turn.hasToolCalls()) {
                String nudge = spec.answerGate().vet(turn.content());
                if (nudge == null || nudged) {
                    // Vetted every time so the gate sees the outcome, but sent back only
                    // once: a gate the model can't satisfy must cost a step, not the budget.
                    return LoopOutcome.answered(turn.content());
                }
                log.info("answer gate sent {} back: {}", agent, nudge);
                messages.add(ChatMessage.assistant(turn.content()));
                messages.add(ChatMessage.user(nudge));
                nudged = true;
                continue;
            }

            messages.add(ChatMessage.assistantToolCalls(turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                if (spec.cancelled().getAsBoolean()) {
                    return LoopOutcome.cancelled(); // step boundary: before each skill run
                }
                messages.add(ChatMessage.toolResult(runTool(spec, caps, context, call)));
            }
        }
        return LoopOutcome.exhausted();
    }

    /**
     * Run one tool call — a pool script skill or an injected builtin. Both paths pass the
     * same hook gates, so {@code block-secrets} guards {@code delegate} exactly as it
     * guards {@code echo}.
     */
    private String runTool(LoopSpec spec, AgentCapabilities caps, Map<String, Object> context, ToolCall call) {
        String agent = spec.agent();
        String conversationId = spec.conversationId();
        events.emit(WsEvent.status(conversationId, agent, "working", "running " + call.name()));

        // before_skill hooks — a blocking safety gate (e.g. block-secrets) may veto.
        HookOutcome before = hooks.dispatch("before_skill", caps,
                Map.of("skill", call.name(), "input", call.arguments()), context);
        if (before.blocked()) {
            events.emit(WsEvent.error(conversationId, agent,
                    "Skill '" + call.name() + "' blocked: " + before.reason()));
            return "Refused: " + before.reason();
        }

        BuiltinTool builtin = spec.builtins().stream()
                .filter(b -> b.name().equals(call.name())).findFirst().orElse(null);
        SkillManifest skill = caps.skills().stream()
                .filter(s -> s.name().equals(call.name()) && s.type() == SkillManifest.Type.SCRIPT)
                .findFirst().orElse(null);
        if (builtin == null && skill == null) {
            return "Error: no such skill '" + call.name() + "'";
        }

        events.emit(WsEvent.toolCall(conversationId, agent, call.name(), argsToString(call.arguments())));
        spec.observer().onToolCall(call);

        String output;
        String summary;
        if (builtin != null) {
            output = builtin.handler().apply(call);
            summary = "ok";
        } else {
            Map<String, Object> request = Map.of("skill", call.name(), "input", call.arguments(), "context", context);
            RunnerResult r = runner.run(skill.entrypointPath(), request,
                    Duration.ofSeconds(skill.timeoutSeconds()), skillEnv.forSkills());
            JsonNode json = r.json();
            if (r.ok() && json != null && json.path("ok").asBoolean(false)) {
                output = json.path("output").asText("");
            } else {
                output = "Error: " + (r.stderr().isBlank() ? "skill failed" : r.stderr().trim());
            }
            summary = json != null && json.hasNonNull("display") ? json.get("display").asText() : "ok";
        }

        HookOutcome after = hooks.dispatch("after_skill", caps,
                Map.of("skill", call.name(), "output", output), context);
        if (after.outputRewrite() != null) {
            output = after.outputRewrite();
        }
        events.emit(WsEvent.toolResult(conversationId, agent, call.name(), summary));
        spec.observer().onToolResult(call, output);
        return output;
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

    private String argsToString(Map<String, Object> args) {
        try {
            return args.isEmpty() ? "" : mapper.writeValueAsString(args);
        } catch (Exception e) {
            return "";
        }
    }
}
