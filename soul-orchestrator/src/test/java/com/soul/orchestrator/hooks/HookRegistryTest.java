package com.soul.orchestrator.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HookRegistryTest {

    private static final Path POOL = Path.of("../hookspool");

    @Test
    void loadsEveryHookInThePool() {
        HookRegistry registry = new HookRegistry(POOL, true);
        assertThat(registry.all().keySet()).contains("audit-log", "block-secrets", "inject-time");
    }

    @Test
    void parsesEventListAndSingleEvent() {
        HookRegistry registry = new HookRegistry(POOL, true);
        assertThat(registry.get("audit-log").orElseThrow().events())
                .containsExactlyInAnyOrder("before_skill", "after_skill");
        assertThat(registry.get("inject-time").orElseThrow().events())
                .containsExactly("before_model");
    }

    @Test
    void identifiesAlwaysApplySafetyGates() {
        HookRegistry registry = new HookRegistry(POOL, true);
        assertThat(registry.alwaysApply()).extracting(HookManifest::name).contains("block-secrets");
        assertThat(registry.get("block-secrets").orElseThrow().blocking()).isTrue();
    }
}
