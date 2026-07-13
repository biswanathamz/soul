package com.soul.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.soul.orchestrator.agent.AgentCapabilities;
import com.soul.orchestrator.agent.CapabilityResolver;
import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.hooks.HookManifest;
import com.soul.orchestrator.skills.SkillManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Context loads and application.yml binds; the Manager resolves against the real pools. */
@SpringBootTest
class SoulOrchestratorApplicationTests {

    @Autowired
    SoulProperties props;

    @Autowired
    CapabilityResolver resolver;

    @Test
    void bindsPerAgentConfig() {
        SoulProperties.Agent manager = props.getAgents().get("super");
        assertThat(manager).isNotNull();
        assertThat(manager.getModel()).isEqualTo("llama3.1:8b");
        assertThat(manager.getMaxSteps()).isEqualTo(6);
        assertThat(manager.getSkills()).contains("echo", "current-time", "persona");
    }

    @Test
    void resolvesManagerCapabilitiesFromRealPools() {
        AgentCapabilities caps = resolver.resolve("super");
        assertThat(caps.skills()).extracting(SkillManifest::name)
                .contains("echo", "current-time", "persona");
        assertThat(caps.hooks()).extracting(HookManifest::name)
                .contains("audit-log", "block-secrets", "inject-time");
    }
}
