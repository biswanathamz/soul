package com.soul.orchestrator.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The standard shape of every {@code task.completed} payload (docs/researcher-agent.md
 * §3.2b) — so the Manager reasons about ANY worker's result the same way.
 *
 * <p>Confidence is generic on purpose: a researcher rates how sure it is of its
 * findings, a future {@code email.send} worker how sure it is the mail went out. What
 * the Manager DOES with the number is policy, and lives in the delegate tool (§5.1).
 */
public record TaskResult(double confidence, String summary, Map<String, Object> data) {

    public TaskResult {
        confidence = clamp(confidence);
        summary = summary == null ? "" : summary;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * The hard evidence ceiling for a result backed by {@code sources} independent
     * sources (§4.3). A small model's self-rating is not trustworthy alone, so it never
     * outranks what was actually gathered.
     */
    public static double evidenceCap(int sources) {
        if (sources <= 0) {
            return 0.2;
        }
        return sources == 1 ? 0.6 : 1.0;
    }

    /** {@code confidence = min(model_self_assessment, evidence_cap)} — signals, not vibes. */
    public static TaskResult withEvidenceCap(double selfAssessment, int sources, String summary,
            Map<String, Object> data) {
        return new TaskResult(Math.min(clamp(selfAssessment), evidenceCap(sources)), summary, data);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confidence", confidence);
        payload.put("summary", summary);
        payload.put("data", data);
        return payload;
    }

    @SuppressWarnings("unchecked")
    public static TaskResult fromPayload(Map<String, Object> payload) {
        Object confidence = payload.get("confidence");
        Object summary = payload.get("summary");
        Object data = payload.get("data");
        return new TaskResult(
                confidence instanceof Number number ? number.doubleValue() : 0.0,
                summary == null ? "" : summary.toString(),
                data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }
}
