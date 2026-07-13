package com.soul.orchestrator.ollama;

import com.soul.orchestrator.skills.SkillManifest;
import java.util.Map;

/**
 * A model-facing tool definition. Built from a script skill's manifest — its
 * description and JSON-Schema parameters are what the model uses to decide
 * (docs/manager-agent.md §3.3).
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    public static ToolSpec fromSkill(SkillManifest skill) {
        return new ToolSpec(skill.name(), skill.description(), skill.parameters());
    }
}
