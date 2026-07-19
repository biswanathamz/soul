package com.soul.orchestrator.agent;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.ollama.ToolSpec;
import com.soul.orchestrator.protocol.AgentCommand;
import com.soul.orchestrator.protocol.AgentDescriptor;
import com.soul.orchestrator.protocol.AgentEvent;
import com.soul.orchestrator.protocol.AgentRegistry;
import com.soul.orchestrator.protocol.PendingDelegations;
import com.soul.orchestrator.protocol.TaskResult;
import com.soul.orchestrator.ws.EventSink;
import com.soul.orchestrator.ws.WsEvent;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Manager's one and only delegation tool (docs/researcher-agent.md §5) — generic,
 * routed by capability, identical whether SOUL has one worker or twelve.
 *
 * <p>Delegation is orchestration rather than a skill, so this is injected as a
 * {@link BuiltinTool} instead of living in the pool. The tool's {@code capability} enum
 * and description are <b>generated from the registry</b>: adding a worker means declaring
 * capabilities in config, and nothing here changes.
 */
@Component
public class DelegateTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateTool.class);
    private static final String TOOL = "delegate";
    private static final String MANAGER = "super";

    private final AgentRegistry registry;
    private final PendingDelegations pending;
    private final SoulProperties props;
    private final EventSink events;

    public DelegateTool(AgentRegistry registry, PendingDelegations pending, SoulProperties props,
            EventSink events) {
        this.registry = registry;
        this.pending = pending;
        this.props = props;
        this.events = events;
    }

    /**
     * The tool as offered for one conversation — empty when no worker has registered, so
     * a SOUL with no fleet is offered no delegation tool at all.
     */
    public Optional<BuiltinTool> forConversation(String conversationId) {
        if (registry.capabilities().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BuiltinTool(spec(), call -> handle(conversationId, call)));
    }

    private ToolSpec spec() {
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("type", "string");
        capability.put("enum", List.copyOf(registry.capabilities()));
        capability.put("description", "The specialist capability this task needs");

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "The task, stated in full — the specialist sees no conversation history");

        return new ToolSpec(TOOL, description(),
                Map.of("type", "object",
                        "properties", Map.of("capability", capability, "task", task),
                        "required", List.of("capability", "task")));
    }

    /** Generated from the registry, grouped by provider — never hand-maintained. */
    private String description() {
        StringBuilder sb = new StringBuilder(
                "Hand a task to a specialist agent and wait for its result.\n\n"
                + "USE IT when the answer depends on something you cannot know from training: "
                + "today's facts, current versions, prices, news, weather, scores, or anything "
                + "that may have changed. If you are about to state a fact that could be out of "
                + "date, delegate instead.\n"
                + "DO NOT USE IT for anything you can already do: arithmetic, definitions, "
                + "grammar, writing, code, reasoning, or facts that do not change. Delegating "
                + "costs the user around a minute of waiting, so \"what is 2+2\" or \"write me a "
                + "haiku\" must be answered directly, right now.\n\n"
                + "Pick the capability that matches the need:\n");
        for (AgentDescriptor agent : registry.available()) {
            Set<String> owned = capabilitiesOf(agent);
            if (owned.isEmpty()) {
                continue;
            }
            sb.append("- ").append(String.join(", ", owned))
                    .append(" (").append(agent.name());
            if (!agent.description().isBlank()) {
                sb.append(" — ").append(agent.description());
            }
            sb.append(")\n");
        }
        sb.append("Any capability of an agent hands the whole task to that agent. Always "
                + "summarize what comes back in your own words and cite the sources — never "
                + "dump the raw findings at the user.");
        return sb.toString();
    }

    /** Only the capabilities this agent actually won — a duplicate provider advertises none. */
    private Set<String> capabilitiesOf(AgentDescriptor agent) {
        Set<String> owned = new LinkedHashSet<>();
        for (String capability : registry.capabilities()) {
            if (registry.whoSupports(capability).filter(a -> a.name().equals(agent.name())).isPresent()) {
                owned.add(capability);
            }
        }
        return owned;
    }

    private String handle(String conversationId, ToolCall call) {
        String capability = string(call.arguments().get("capability"));
        String task = string(call.arguments().get("task"));
        if (task.isBlank()) {
            return "Error: 'task' is required — state the task in full.";
        }
        Optional<AgentDescriptor> provider = registry.whoSupports(capability);
        if (provider.isEmpty()) {
            // A clean tool error the model can recover from, never a crash (§5).
            return "Error: no agent supports '" + capability + "'. Available capabilities: "
                    + String.join(", ", registry.capabilities());
        }
        return delegate(conversationId, provider.get(), capability, task);
    }

    /**
     * The confidence policy (§5.1) — deterministic orchestrator code. An 8B model is
     * trusted to PRESENT a result; it is never trusted to decide retry-vs-hedge-vs-tell.
     */
    private String delegate(String conversationId, AgentDescriptor agent, String capability, String task) {
        SoulProperties.Confidence policy = props.getDelegation().getConfidence();
        Duration timeout = Duration.ofSeconds(props.getDelegation().getTimeoutSeconds());

        AgentEvent outcome = dispatch(conversationId, agent, capability, task, 1, List.of(), timeout);
        int attempts = 1;
        for (int attempt = 2; attempt <= policy.getMaxRetries() + 1; attempt++) {
            if (!isLowConfidence(outcome, policy)) {
                break;
            }
            // "Ask another source": a fresh command excluding what attempt 1 already read.
            // The user sees a second delegation — SOUL double-checking is a feature.
            log.info("delegation to {} came back at {} — retrying with different sources",
                    agent.name(), outcome.result().confidence());
            outcome = dispatch(conversationId, agent, capability, task, attempt,
                    domainsOf(outcome.result()), timeout);
            attempts++;
        }
        return present(outcome, policy, attempts);
    }

    private boolean isLowConfidence(AgentEvent outcome, SoulProperties.Confidence policy) {
        return AgentEvent.COMPLETED.equals(outcome.type())
                && outcome.result().confidence() < policy.getRetryBelow();
    }

    private AgentEvent dispatch(String conversationId, AgentDescriptor agent, String capability,
            String task, int attempt, List<String> excludeDomains, Duration timeout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", task);
        payload.put("capability", capability);
        payload.put("attempt", attempt);
        if (!excludeDomains.isEmpty()) {
            payload.put("excludeDomains", excludeDomains);
        }
        AgentCommand command = AgentCommand.task(MANAGER, agent.name(), conversationId, payload);
        String id = command.id().toString();
        events.emit(WsEvent.delegation(conversationId, MANAGER, agent.name(), task, id, attempt));

        AgentEvent outcome = pending.dispatchAndAwait(command, timeout);

        // Publish the evidence, not just the verdict: the UI shows the user the confidence
        // and the sources so they can judge the answer for themselves.
        TaskResult result = AgentEvent.COMPLETED.equals(outcome.type()) ? outcome.result() : null;
        events.emit(WsEvent.delegationResult(conversationId, agent.name(), id,
                statusOf(outcome), result == null ? null : result.confidence(),
                result == null ? null : sourcesOf(result)));
        return outcome;
    }

    /** The lifecycle event type as the wire's short status: task.completed → "completed". */
    private static String statusOf(AgentEvent outcome) {
        String type = outcome.type();
        int dot = type.indexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    /** What the model actually receives back — the number is policy, the wording is guidance. */
    private String present(AgentEvent outcome, SoulProperties.Confidence policy, int attempts) {
        if (AgentEvent.CANCELLED.equals(outcome.type())) {
            return "The task was cancelled by the user. Acknowledge briefly that you stopped; "
                    + "do not answer the original question.";
        }
        if (!AgentEvent.COMPLETED.equals(outcome.type())) {
            return "The task failed: " + outcome.reason()
                    + ". Tell the user honestly that you could not find this out — do not answer "
                    + "from memory.";
        }

        TaskResult result = outcome.result();
        double confidence = result.confidence();
        String findings = result.summary();

        if (confidence >= policy.getHedgeBelow()) {
            return "(confidence " + percent(confidence) + ", " + sourceCount(result)
                    + ") Summarize these findings for the user and cite the sources:\n\n" + findings;
        }
        if (confidence >= policy.getRetryBelow()) {
            return "(moderate confidence " + percent(confidence) + ", " + sourceCount(result)
                    + " — hedge accordingly: give the answer, but make clear it could not be fully "
                    + "verified)\n\n" + findings;
        }

        // The findings are WITHHELD, not merely disclaimed. Told "(LOW confidence 20% — do
        // NOT present this as fact)" and then handed the findings anyway, llama3.1:8b read
        // past the warning and stated a fabricated Node version as fact (§9). A model
        // cannot parrot evidence it was never given, so below the retry threshold it isn't
        // given any — the only thing left to say is the truth.
        log.info("withholding {} findings from the Manager: confidence {} after {} attempt(s)",
                agent(outcome), confidence, attempts);
        return "The research FAILED to establish this: confidence " + percent(confidence)
                + " after " + attempts + " attempt" + (attempts == 1 ? "" : "s") + ", with "
                + sourceCount(result) + " verified. The findings were too weak to repeat and have "
                + "been withheld from you deliberately.\n\n"
                + "Tell the user plainly that you could not find a reliable answer, and that you "
                + "would rather say so than guess. State NO version, number, date, name or fact "
                + "in your reply — not from the research, and not from your own memory, which is "
                + "out of date for exactly this kind of question. Offer to try again if they like.";
    }

    private static String agent(AgentEvent outcome) {
        return outcome.agent() == null ? "worker" : outcome.agent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sourcesOf(TaskResult result) {
        Object sources = result.data().get("sources");
        return sources instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** The domains attempt 1 already read — attempt 2 must not simply read them again. */
    private static List<String> domainsOf(TaskResult result) {
        Set<String> domains = new LinkedHashSet<>();
        for (Map<String, Object> source : sourcesOf(result)) {
            Object url = source.get("url");
            if (url == null) {
                continue;
            }
            try {
                String host = URI.create(url.toString().trim()).getHost();
                if (host != null) {
                    domains.add(host.replaceFirst("^www\\.", ""));
                }
            } catch (IllegalArgumentException e) {
                log.debug("unparseable source url: {}", url);
            }
        }
        return List.copyOf(domains);
    }

    private static String sourceCount(TaskResult result) {
        int count = sourcesOf(result).size();
        return count + " source" + (count == 1 ? "" : "s");
    }

    private static String percent(double confidence) {
        return Math.round(confidence * 100) + "%";
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
