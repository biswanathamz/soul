package com.soul.orchestrator.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.soul.orchestrator.agent.AgentCapabilities;
import com.soul.orchestrator.runtime.Runner;
import com.soul.orchestrator.runtime.RunnerResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the hooks registered for a lifecycle event, in order, and folds their
 * allow/modify/block results into one {@link HookOutcome} (docs/manager-agent.md §4.3).
 * The first block from a blocking hook short-circuits; a block from a non-blocking hook
 * is logged and ignored. A blocking hook that times out fails closed (blocks).
 */
@Component
public class HookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);

    private final Runner runner;

    public HookDispatcher(Runner runner) {
        this.runner = runner;
    }

    public HookOutcome dispatch(String event, AgentCapabilities caps, Map<String, Object> payload,
            Map<String, Object> context) {
        List<HookManifest> hooks = caps.hooksFor(event);
        List<String> appendSystem = new ArrayList<>();
        String messageRewrite = null;
        String outputRewrite = null;

        for (HookManifest hook : hooks) {
            Map<String, Object> request = Map.of("event", event, "payload", payload, "context", context);
            RunnerResult result = runner.run(
                    hook.entrypointPath(), request, Duration.ofSeconds(hook.timeoutSeconds()));
            JsonNode json = result.json();
            String action = json != null ? json.path("action").asText("allow") : "allow";

            boolean wantsBlock = (!result.timedOut() && result.exitCode() != 0) || "block".equals(action);
            boolean timedOutBlocking = result.timedOut() && hook.blocking();

            if (wantsBlock || timedOutBlocking) {
                if (hook.blocking()) {
                    return HookOutcome.blocked(reasonFrom(hook, result, json));
                }
                log.warn("non-blocking hook '{}' tried to block; ignoring", hook.name());
            }

            if ("modify".equals(action) && json != null && json.has("patch")) {
                JsonNode patch = json.get("patch");
                if (patch.hasNonNull("append_system")) {
                    appendSystem.add(patch.get("append_system").asText());
                }
                if (patch.hasNonNull("message")) {
                    messageRewrite = patch.get("message").asText();
                }
                if (patch.hasNonNull("output")) {
                    outputRewrite = patch.get("output").asText();
                }
            }
        }
        return new HookOutcome(false, null, appendSystem, messageRewrite, outputRewrite);
    }

    private static String reasonFrom(HookManifest hook, RunnerResult result, JsonNode json) {
        if (json != null && json.hasNonNull("reason")) {
            return json.get("reason").asText();
        }
        if (result.timedOut()) {
            return hook.name() + " timed out";
        }
        return result.stderr().isBlank() ? "blocked by " + hook.name() : result.stderr().trim();
    }
}
