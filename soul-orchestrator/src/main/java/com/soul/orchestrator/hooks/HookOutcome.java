package com.soul.orchestrator.hooks;

import java.util.List;

/**
 * Aggregated result of running an event's hooks. If {@code blocked}, the step is
 * vetoed with {@code reason}. Otherwise the modify patches are applied: system-context
 * additions, a rewritten user message, or a rewritten skill output.
 */
public record HookOutcome(
        boolean blocked,
        String reason,
        List<String> appendSystem,
        String messageRewrite,
        String outputRewrite) {

    public static HookOutcome blocked(String reason) {
        return new HookOutcome(true, reason, List.of(), null, null);
    }
}
