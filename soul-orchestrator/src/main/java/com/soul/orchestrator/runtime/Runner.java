package com.soul.orchestrator.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared subprocess runner for skills and hooks (docs/manager-agent.md §3.4, §4.3).
 * Executes an entrypoint by its shebang, writes a JSON request to stdin, and captures
 * stdout/stderr concurrently so a chatty process can't deadlock on a full pipe. The
 * process is killed if it overruns {@code timeout}.
 *
 * <p>Phase-2 isolation is a timeout plus the caller-chosen working directory; the
 * full sandbox (scrubbed env, resource limits, permission enforcement) lands in
 * phase 5.
 */
@Component
public class Runner {

    private static final Logger log = LoggerFactory.getLogger(Runner.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public RunnerResult run(Path entrypoint, Map<String, Object> request, Duration timeout) {
        return run(entrypoint, request, timeout, Map.of());
    }

    /**
     * As above, with extra environment variables for the child process. This is how
     * config reaches a skill's connectors — {@code SOUL_SEARCH_PROVIDER} and friends
     * (docs/researcher-agent.md §4.3) — without the skill parsing SOUL's config itself.
     */
    public RunnerResult run(Path entrypoint, Map<String, Object> request, Duration timeout,
            Map<String, String> env) {
        String requestJson;
        try {
            requestJson = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize runner request", e);
        }

        ProcessBuilder pb = new ProcessBuilder(entrypoint.toAbsolutePath().toString());
        pb.directory(entrypoint.getParent().toFile());
        pb.environment().putAll(env);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // e.g. missing shebang / not executable
            return new RunnerResult(-1, "", "cannot execute " + entrypoint + ": " + e.getMessage(), false);
        }

        CompletableFuture<String> out = readAsync(process.getInputStream());
        CompletableFuture<String> err = readAsync(process.getErrorStream());

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(requestJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Process may have exited before reading stdin — carry on to collect its output.
            log.debug("stdin write to {} failed: {}", entrypoint, e.getMessage());
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new RunnerResult(-1, "", "interrupted", true);
        }

        if (!finished) {
            process.destroyForcibly();
            return new RunnerResult(-1, out.join(), "timed out after " + timeout.toMillis() + "ms", true);
        }
        return new RunnerResult(process.exitValue(), out.join(), err.join(), false);
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
