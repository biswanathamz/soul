package com.soul.orchestrator.agent;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.hooks.HookManifest;
import com.soul.orchestrator.hooks.HookRegistry;
import com.soul.orchestrator.skills.SkillManifest;
import com.soul.orchestrator.skills.SkillRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds an agent's {@link AgentCapabilities} from its config list against the global
 * registries (docs/manager-agent.md §5). A name must resolve in the pool (typo guard);
 * {@code "*"} grants everything; always-apply hooks are merged in regardless.
 */
@Component
public class CapabilityResolver {

    private final SoulProperties props;
    private final SkillRegistry skills;
    private final HookRegistry hooks;

    public CapabilityResolver(SoulProperties props, SkillRegistry skills, HookRegistry hooks) {
        this.props = props;
        this.skills = skills;
        this.hooks = hooks;
    }

    public AgentCapabilities resolve(String agentName) {
        SoulProperties.Agent cfg = props.getAgents().get(agentName);
        if (cfg == null) {
            throw new IllegalArgumentException("no such agent in config: " + agentName);
        }

        List<SkillManifest> agentSkills = new ArrayList<>();
        if (isWildcard(cfg.getSkills())) {
            agentSkills.addAll(skills.all().values());
        } else {
            for (String name : cfg.getSkills()) {
                agentSkills.add(skills.get(name).orElseThrow(
                        () -> new IllegalStateException("agent '" + agentName + "' lists unknown skill: " + name)));
            }
        }

        // Merge the agent's hooks with always-apply safety gates, de-duplicated, order preserved.
        Map<String, HookManifest> merged = new LinkedHashMap<>();
        if (isWildcard(cfg.getHooks())) {
            hooks.all().values().forEach(h -> merged.put(h.name(), h));
        } else {
            for (String name : cfg.getHooks()) {
                merged.put(name, hooks.get(name).orElseThrow(
                        () -> new IllegalStateException("agent '" + agentName + "' lists unknown hook: " + name)));
            }
        }
        for (HookManifest gate : hooks.alwaysApply()) {
            merged.putIfAbsent(gate.name(), gate);
        }

        return new AgentCapabilities(agentName, List.copyOf(agentSkills), List.copyOf(merged.values()));
    }

    private static boolean isWildcard(List<String> list) {
        return list != null && list.size() == 1 && "*".equals(list.get(0));
    }
}
