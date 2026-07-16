package com.soul.orchestrator.agent;

import com.soul.orchestrator.config.SoulProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns SOUL's config into environment variables for skill subprocesses
 * (docs/researcher-agent.md §4.3). Provider API keys are not handled here: they live in
 * the orchestrator's own environment and are inherited by the child process, so no
 * secret is ever copied through SOUL's config.
 */
@Component
public class SkillEnvironment {

    private final SoulProperties props;

    public SkillEnvironment(SoulProperties props) {
        this.props = props;
    }

    public Map<String, String> forSkills() {
        SoulProperties.Search search = props.getResearch().getSearch();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SOUL_SEARCH_PROVIDER", search.getProvider());
        env.put("SOUL_SEARCH_FALLBACKS", String.join(",", search.getFallbacks()));
        env.put("SOUL_MAX_SOURCES", String.valueOf(props.getResearch().getMaxSources()));
        return env;
    }
}
