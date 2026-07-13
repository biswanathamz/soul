package com.soul.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.hooks.HookManifest;
import com.soul.orchestrator.hooks.HookRegistry;
import com.soul.orchestrator.skills.SkillManifest;
import com.soul.orchestrator.skills.SkillRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CapabilityResolverTest {

    private final SkillRegistry skills = new SkillRegistry(Path.of("../skillpool"), true);
    private final HookRegistry hooks = new HookRegistry(Path.of("../hookspool"), true);

    private CapabilityResolver resolverFor(List<String> skillList, List<String> hookList) {
        SoulProperties props = new SoulProperties();
        SoulProperties.Agent agent = new SoulProperties.Agent();
        agent.setModel("llama3.1:8b");
        agent.setSkills(skillList);
        agent.setHooks(hookList);
        props.setAgents(Map.of("super", agent));
        return new CapabilityResolver(props, skills, hooks);
    }

    @Test
    void resolvesOnlyTheListedSkills() {
        AgentCapabilities caps = resolverFor(List.of("echo", "current-time"), List.of("audit-log")).resolve("super");
        assertThat(caps.skills()).extracting(SkillManifest::name)
                .containsExactly("echo", "current-time");
    }

    @Test
    void alwaysApplyHookIsMergedEvenWhenNotListed() {
        // agent lists only audit-log; block-secrets must still be present (always-apply).
        AgentCapabilities caps = resolverFor(List.of("echo"), List.of("audit-log")).resolve("super");
        assertThat(caps.hooks()).extracting(HookManifest::name)
                .contains("audit-log", "block-secrets");
    }

    @Test
    void alwaysApplyHookIsNotDuplicatedWhenAlsoListed() {
        AgentCapabilities caps =
                resolverFor(List.of("echo"), List.of("audit-log", "block-secrets")).resolve("super");
        assertThat(caps.hooks()).extracting(HookManifest::name)
                .filteredOn(n -> n.equals("block-secrets")).hasSize(1);
    }

    @Test
    void wildcardGrantsTheWholePool() {
        AgentCapabilities caps = resolverFor(List.of("*"), List.of("*")).resolve("super");
        assertThat(caps.skills()).hasSize(skills.all().size());
    }

    @Test
    void unknownSkillNameFailsFast() {
        assertThatThrownBy(() -> resolverFor(List.of("nope"), List.of()).resolve("super"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown skill");
    }

    @Test
    void unknownAgentThrows() {
        assertThatThrownBy(() -> resolverFor(List.of("echo"), List.of()).resolve("ghost"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hooksForEventFilters() {
        AgentCapabilities caps =
                resolverFor(List.of("echo"), List.of("audit-log", "inject-time")).resolve("super");
        assertThat(caps.hooksFor("before_model")).extracting(HookManifest::name).contains("inject-time");
        assertThat(caps.hooksFor("before_skill")).extracting(HookManifest::name)
                .contains("audit-log", "block-secrets");
    }
}
