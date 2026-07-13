package com.soul.orchestrator.skills;

import com.soul.orchestrator.config.SoulProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Global skill registry — scans {@code skillpool/} once at startup and validates
 * every manifest (docs/manager-agent.md §3.3). Agents get filtered views of this
 * (see CapabilityResolver); the registry itself is agent-agnostic.
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillManifest> skills;

    @Autowired
    public SkillRegistry(SoulProperties props) {
        this(Path.of(props.getPools().getSkills().getPath()), props.getPools().getSkills().isEnabled());
    }

    /** Test-friendly: load a specific pool directory. */
    public SkillRegistry(Path poolDir, boolean enabled) {
        this.skills = enabled ? load(poolDir) : Map.of();
        log.info("Loaded {} skill(s) from {}", skills.size(), poolDir);
    }

    private static Map<String, SkillManifest> load(Path poolDir) {
        if (!Files.isDirectory(poolDir)) {
            throw new IllegalStateException("skill pool not found: " + poolDir.toAbsolutePath());
        }
        Map<String, SkillManifest> loaded = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(poolDir)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("skill.yaml")))
                    .sorted()
                    .forEach(d -> {
                        SkillManifest skill = SkillManifest.load(d);
                        if (loaded.put(skill.name(), skill) != null) {
                            throw new IllegalStateException("duplicate skill name: " + skill.name());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan skill pool " + poolDir, e);
        }
        return loaded;
    }

    public Optional<SkillManifest> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public boolean contains(String name) {
        return skills.containsKey(name);
    }

    public Map<String, SkillManifest> all() {
        return Map.copyOf(skills);
    }
}
