package com.soul.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Drives the real skill/hook entrypoints through the subprocess runner. */
class RunnerTest {

    private final Runner runner = new Runner();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void runsEchoSkillAndParsesResult() {
        RunnerResult r = runner.run(
                Path.of("../skillpool/echo/run.py"),
                Map.of("skill", "echo", "input", Map.of("text", "hi there"), "context", Map.of()),
                TIMEOUT);
        assertThat(r.ok()).isTrue();
        assertThat(r.json().get("ok").asBoolean()).isTrue();
        assertThat(r.json().get("output").asText()).isEqualTo("hi there");
    }

    @Test
    void currentTimeSkillSucceeds() {
        RunnerResult r = runner.run(
                Path.of("../skillpool/current-time/run.py"),
                Map.of("skill", "current-time", "input", Map.of()),
                TIMEOUT);
        assertThat(r.ok()).isTrue();
        assertThat(r.json().get("ok").asBoolean()).isTrue();
    }

    @Test
    void blockSecretsAllowsBenignInput() {
        RunnerResult r = runner.run(
                Path.of("../hookspool/block-secrets/run.py"),
                Map.of("event", "before_skill",
                        "payload", Map.of("skill", "echo", "input", Map.of("text", "harmless"))),
                TIMEOUT);
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void blockSecretsBlocksCredentials() {
        RunnerResult r = runner.run(
                Path.of("../hookspool/block-secrets/run.py"),
                Map.of("event", "before_skill",
                        "payload", Map.of("skill", "echo",
                                "input", Map.of("text", "key AKIAIOSFODNN7EXAMPLE here"))),
                TIMEOUT);
        assertThat(r.exitCode()).isNotZero();
        assertThat(r.json().get("action").asText()).isEqualTo("block");
        // The secret itself must not appear in the surfaced output.
        assertThat(r.stdout() + r.stderr()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    void injectTimeReturnsModifyPatch() {
        RunnerResult r = runner.run(
                Path.of("../hookspool/inject-time/run.py"),
                Map.of("event", "before_model", "payload", Map.of()),
                TIMEOUT);
        assertThat(r.ok()).isTrue();
        assertThat(r.json().get("action").asText()).isEqualTo("modify");
        assertThat(r.json().get("patch").has("append_system")).isTrue();
    }

    @Test
    void killsAnOverrunningProcess() throws Exception {
        Path dir = Files.createTempDirectory("runner-timeout");
        Path script = dir.resolve("slow.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 5\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();

        RunnerResult r = runner.run(script, Map.of(), Duration.ofMillis(300));
        assertThat(r.timedOut()).isTrue();
    }

    @Test
    void missingEntrypointIsReportedNotThrown() {
        RunnerResult r = runner.run(Path.of("../skillpool/echo/nope.py"), Map.of(), TIMEOUT);
        assertThat(r.ok()).isFalse();
        assertThat(r.stderr()).contains("cannot execute");
    }
}
