package com.soul.orchestrator.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Outcome of running a skill or hook entrypoint (docs/manager-agent.md §3.4, §4.3):
 * exit code, captured streams, and whether it was killed for exceeding its timeout.
 */
public record RunnerResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }

    /** Parsed stdout JSON, or null when stdout is blank / not JSON. */
    public JsonNode json() {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(stdout);
        } catch (Exception e) {
            return null;
        }
    }
}
