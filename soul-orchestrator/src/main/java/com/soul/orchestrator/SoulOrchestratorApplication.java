package com.soul.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SOUL orchestrator — the agent runtime. Phase 2 wires the skill/hook registries,
 * per-agent capability resolution, and the shared subprocess runner. The Manager
 * loop, Ollama chat, and hook dispatch land in later phases (docs/manager-agent.md).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SoulOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SoulOrchestratorApplication.class, args);
    }
}
