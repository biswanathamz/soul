package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** "Who supports X?" — the question that keeps the Manager free of agent names. */
class InProcessAgentRegistryTest {

    private final InProcessCommandBus commands = new InProcessCommandBus();
    private final InProcessAgentRegistry registry = new InProcessAgentRegistry(commands);

    private static final AgentDescriptor RESEARCHER = new AgentDescriptor(
            "researcher", "Finds current, real-world information on the web.",
            Set.of("research.search", "research.fetch"));

    private static final Consumer<AgentCommand> NOOP = cmd -> { };

    @Test
    void whoSupportsResolvesTheProvidingAgent() {
        registry.register(RESEARCHER, NOOP);

        assertThat(registry.whoSupports("research.search")).contains(RESEARCHER);
        assertThat(registry.whoSupports("research.fetch")).contains(RESEARCHER);
    }

    @Test
    void unknownCapabilityResolvesToEmptySoTheToolCanReportItCleanly() {
        registry.register(RESEARCHER, NOOP);

        // The delegate tool turns this into "no agent supports: X" — never a crash (§5).
        assertThat(registry.whoSupports("email.send")).isEmpty();
    }

    @Test
    void firstProviderOfACapabilityWins() {
        AgentDescriptor incumbent = new AgentDescriptor("researcher", "first", Set.of("research.search"));
        AgentDescriptor latecomer = new AgentDescriptor("scraper", "second", Set.of("research.search"));

        registry.register(incumbent, NOOP);
        registry.register(latecomer, NOOP);

        assertThat(registry.whoSupports("research.search")).contains(incumbent);
        assertThat(registry.available()).contains(incumbent, latecomer); // both exist…
    }

    @Test
    void capabilitiesUnionFeedsTheDelegateToolsEnum() {
        registry.register(RESEARCHER, NOOP);
        registry.register(new AgentDescriptor("calendar", "Schedules things.", Set.of("task.schedule")), NOOP);

        assertThat(registry.capabilities())
                .containsExactlyInAnyOrder("research.search", "research.fetch", "task.schedule");
    }

    @Test
    void registeringAlsoWiresTheWorkerToTheCommandBus() throws Exception {
        // An agent must never advertise a capability it has no worker to serve.
        CountDownLatch delivered = new CountDownLatch(1);
        registry.register(RESEARCHER, cmd -> delivered.countDown());

        String target = registry.whoSupports("research.search").orElseThrow().name();
        commands.send(AgentCommand.task("super", target, "conv-1", Map.of("task", "x")));

        assertThat(delivered.await(2, SECONDS)).isTrue();
    }
}
