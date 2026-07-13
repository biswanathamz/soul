package com.soul.orchestrator.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * A parsed {@code hook.yaml} plus its directory (docs/manager-agent.md §4.2).
 * {@code event} may be a single value or a list. {@link #load(Path)} validates.
 */
public record HookManifest(
        String name,
        String description,
        List<String> events,
        Path dir,
        String entrypoint,
        String matcher,
        boolean blocking,
        boolean alwaysApply,
        int timeoutSeconds) {

    public static final List<String> VALID_EVENTS = List.of(
            "session_start",
            "user_message_received",
            "before_model",
            "before_skill",
            "after_skill",
            "before_respond",
            "session_end",
            "on_error");

    public Path entrypointPath() {
        return dir.resolve(entrypoint);
    }

    public boolean handles(String event) {
        return events.contains(event);
    }

    @SuppressWarnings("unchecked")
    public static HookManifest load(Path dir) {
        Path file = dir.resolve("hook.yaml");
        Map<String, Object> m;
        try (InputStream in = Files.newInputStream(file)) {
            m = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
        if (m == null) {
            throw new IllegalStateException("empty manifest: " + file);
        }

        String name = str(m, "name", file);
        if (!name.equals(dir.getFileName().toString())) {
            throw new IllegalStateException(
                    "hook name '" + name + "' != directory '" + dir.getFileName() + "'");
        }
        str(m, "description", file);

        Object rawEvent = m.get("event");
        if (rawEvent == null) {
            throw new IllegalStateException("hook.yaml missing 'event': " + file);
        }
        List<String> events = (rawEvent instanceof List)
                ? ((List<Object>) rawEvent).stream().map(Object::toString).toList()
                : List.of(rawEvent.toString());
        for (String ev : events) {
            if (!VALID_EVENTS.contains(ev)) {
                throw new IllegalStateException("unknown hook event '" + ev + "': " + file);
            }
        }

        String entrypoint = str(m, "entrypoint", file);
        String matcher = m.get("matcher") == null ? null : m.get("matcher").toString();
        boolean blocking = Boolean.TRUE.equals(m.get("blocking"));
        boolean alwaysApply = Boolean.TRUE.equals(m.get("always-apply"));
        int timeout = (m.get("timeout_seconds") instanceof Number n) ? n.intValue() : 5;

        return new HookManifest(name, (String) m.get("description"), events, dir, entrypoint,
                matcher, blocking, alwaysApply, timeout);
    }

    private static String str(Map<String, Object> m, String key, Path file) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalStateException("hook.yaml missing '" + key + "': " + file);
        }
        return v.toString().trim();
    }
}
