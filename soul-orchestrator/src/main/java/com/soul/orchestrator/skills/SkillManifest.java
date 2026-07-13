package com.soul.orchestrator.skills;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * A parsed {@code skill.yaml} plus its directory (docs/manager-agent.md §3.2).
 * {@link #load(Path)} validates fields and fails fast on a bad manifest.
 */
public record SkillManifest(
        String name,
        String description,
        String version,
        Type type,
        Path dir,
        String entrypoint,
        Map<String, Object> parameters,
        int timeoutSeconds,
        boolean promptAlways,
        String promptFile) {

    public enum Type {
        SCRIPT,
        PROMPT
    }

    public Path entrypointPath() {
        return dir.resolve(entrypoint);
    }

    public Path promptPath() {
        return dir.resolve(promptFile);
    }

    @SuppressWarnings("unchecked")
    public static SkillManifest load(Path dir) {
        Path file = dir.resolve("skill.yaml");
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
                    "skill name '" + name + "' != directory '" + dir.getFileName() + "'");
        }
        str(m, "description", file);
        str(m, "version", file);
        Type type = parseType(str(m, "type", file), file);

        if (type == Type.SCRIPT) {
            String entrypoint = str(m, "entrypoint", file);
            Object params = m.get("parameters");
            if (!(params instanceof Map)) {
                throw new IllegalStateException("script skill needs a 'parameters' object: " + file);
            }
            int timeout = intOr(m.get("timeout_seconds"), 10);
            return new SkillManifest(name, (String) m.get("description"), String.valueOf(m.get("version")),
                    type, dir, entrypoint, (Map<String, Object>) params, timeout, false, null);
        }

        // prompt skill
        String promptFile = m.getOrDefault("prompt", "prompt.md").toString();
        boolean always = Boolean.TRUE.equals(m.get("always"));
        Path prompt = dir.resolve(promptFile);
        if (!Files.isRegularFile(prompt)) {
            throw new IllegalStateException("prompt skill missing " + prompt);
        }
        return new SkillManifest(name, (String) m.get("description"), String.valueOf(m.get("version")),
                type, dir, null, Map.of(), 0, always, promptFile);
    }

    private static Type parseType(String raw, Path file) {
        try {
            return Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("skill type must be 'script' or 'prompt', got '" + raw + "': " + file);
        }
    }

    private static String str(Map<String, Object> m, String key, Path file) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalStateException("skill.yaml missing '" + key + "': " + file);
        }
        return v.toString().trim();
    }

    private static int intOr(Object v, int fallback) {
        return (v instanceof Number n) ? n.intValue() : fallback;
    }
}
