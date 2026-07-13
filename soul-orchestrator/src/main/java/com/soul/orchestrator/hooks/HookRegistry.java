package com.soul.orchestrator.hooks;

import com.soul.orchestrator.config.SoulProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Global hook registry — scans {@code hookspool/} once at startup and validates
 * every manifest (docs/manager-agent.md §4). Agents get filtered views; hooks with
 * {@code always-apply: true} additionally run for every agent.
 */
@Component
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    private final Map<String, HookManifest> hooks;

    @Autowired
    public HookRegistry(SoulProperties props) {
        this(Path.of(props.getPools().getHooks().getPath()), props.getPools().getHooks().isEnabled());
    }

    /** Test-friendly: load a specific pool directory. */
    public HookRegistry(Path poolDir, boolean enabled) {
        this.hooks = enabled ? load(poolDir) : Map.of();
        log.info("Loaded {} hook(s) from {} ({} always-apply)", hooks.size(), poolDir, alwaysApply().size());
    }

    private static Map<String, HookManifest> load(Path poolDir) {
        if (!Files.isDirectory(poolDir)) {
            throw new IllegalStateException("hook pool not found: " + poolDir.toAbsolutePath());
        }
        Map<String, HookManifest> loaded = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(poolDir)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("hook.yaml")))
                    .sorted()
                    .forEach(d -> {
                        HookManifest hook = HookManifest.load(d);
                        if (loaded.put(hook.name(), hook) != null) {
                            throw new IllegalStateException("duplicate hook name: " + hook.name());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan hook pool " + poolDir, e);
        }
        return loaded;
    }

    public Optional<HookManifest> get(String name) {
        return Optional.ofNullable(hooks.get(name));
    }

    public boolean contains(String name) {
        return hooks.containsKey(name);
    }

    public Map<String, HookManifest> all() {
        return Map.copyOf(hooks);
    }

    /** Hooks that run for every agent regardless of its config list (safety gates). */
    public List<HookManifest> alwaysApply() {
        return hooks.values().stream().filter(HookManifest::alwaysApply).toList();
    }
}
