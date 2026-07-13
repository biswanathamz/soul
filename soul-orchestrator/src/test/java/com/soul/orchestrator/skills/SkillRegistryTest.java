package com.soul.orchestrator.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {

    private static final Path POOL = Path.of("../skillpool");

    @Test
    void loadsEverySkillInThePool() {
        SkillRegistry registry = new SkillRegistry(POOL, true);
        assertThat(registry.all().keySet()).contains("echo", "current-time", "persona");
    }

    @Test
    void classifiesScriptAndPromptSkills() {
        SkillRegistry registry = new SkillRegistry(POOL, true);
        assertThat(registry.get("echo")).get()
                .extracting(SkillManifest::type).isEqualTo(SkillManifest.Type.SCRIPT);
        assertThat(registry.get("persona")).get()
                .extracting(SkillManifest::type).isEqualTo(SkillManifest.Type.PROMPT);
    }

    @Test
    void scriptSkillExposesEntrypointAndParameters() {
        SkillManifest echo = new SkillRegistry(POOL, true).get("echo").orElseThrow();
        assertThat(echo.entrypointPath()).exists();
        assertThat(echo.parameters()).containsKey("properties");
    }

    @Test
    void disabledPoolLoadsNothing() {
        assertThat(new SkillRegistry(POOL, false).all()).isEmpty();
    }

    @Test
    void missingPoolFailsFast() {
        assertThatThrownBy(() -> new SkillRegistry(Path.of("../does-not-exist"), true))
                .isInstanceOf(IllegalStateException.class);
    }
}
