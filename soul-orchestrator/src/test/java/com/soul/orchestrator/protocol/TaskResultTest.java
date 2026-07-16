package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Confidence is signals, not vibes: evidence caps outrank what the model claims. */
class TaskResultTest {

    @Test
    void evidenceCapsBeatAnOverconfidentModel() {
        // An 8B model happily rates itself 0.99 having found nothing at all.
        assertThat(TaskResult.withEvidenceCap(0.99, 0, "found nothing", Map.of()).confidence())
                .isEqualTo(0.2);
        assertThat(TaskResult.withEvidenceCap(0.99, 1, "one blog says so", Map.of()).confidence())
                .isEqualTo(0.6);
        assertThat(TaskResult.withEvidenceCap(0.94, 3, "three sources agree", Map.of()).confidence())
                .isEqualTo(0.94);
    }

    @Test
    void anHonestlyLowSelfAssessmentIsNeverRaisedByEvidence() {
        // The cap is a ceiling, not a floor — sources disagreeing keeps the model's doubt.
        assertThat(TaskResult.withEvidenceCap(0.3, 5, "sources contradict each other", Map.of()).confidence())
                .isEqualTo(0.3);
    }

    @Test
    void confidenceIsClampedToItsRange() {
        assertThat(new TaskResult(1.7, "s", Map.of()).confidence()).isEqualTo(1.0);
        assertThat(new TaskResult(-0.5, "s", Map.of()).confidence()).isEqualTo(0.0);
        assertThat(new TaskResult(Double.NaN, "s", Map.of()).confidence()).isEqualTo(0.0);
    }

    @Test
    void roundTripsThroughAnEventPayload() {
        TaskResult original = new TaskResult(0.94, "Node.js 22 'Jod' is the current LTS.",
                Map.of("sources", List.of(Map.of("title", "Node.js", "url", "https://nodejs.org"))));

        TaskResult restored = TaskResult.fromPayload(original.toPayload());

        assertThat(restored.confidence()).isCloseTo(0.94, within(1e-9));
        assertThat(restored.summary()).isEqualTo(original.summary());
        assertThat(restored.data()).isEqualTo(original.data());
    }

    @Test
    void aMalformedPayloadDegradesToZeroConfidence() {
        // Never let a broken worker payload masquerade as a confident answer.
        assertThat(TaskResult.fromPayload(Map.of()).confidence()).isEqualTo(0.0);
    }
}
