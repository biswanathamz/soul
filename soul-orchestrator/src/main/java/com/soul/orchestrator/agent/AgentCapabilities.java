package com.soul.orchestrator.agent;

import com.soul.orchestrator.hooks.HookManifest;
import com.soul.orchestrator.skills.SkillManifest;
import java.util.List;

/**
 * The filtered view of the shared pools for one agent (docs/manager-agent.md §5):
 * the skills and hooks it may use. {@code hooks} already includes always-apply
 * safety gates, whether or not the agent listed them.
 */
public record AgentCapabilities(String agent, List<SkillManifest> skills, List<HookManifest> hooks) {

    public List<HookManifest> hooksFor(String event) {
        return hooks.stream().filter(h -> h.handles(event)).toList();
    }

    public List<SkillManifest> scriptSkills() {
        return skills.stream().filter(s -> s.type() == SkillManifest.Type.SCRIPT).toList();
    }

    public List<SkillManifest> promptSkills() {
        return skills.stream().filter(s -> s.type() == SkillManifest.Type.PROMPT).toList();
    }
}
