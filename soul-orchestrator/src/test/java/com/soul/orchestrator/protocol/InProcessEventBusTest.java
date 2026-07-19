package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InProcessEventBusTest {

    private static final AgentCommand COMMAND =
            AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "x"));

    private final InProcessEventBus bus = new InProcessEventBus();

    @Test
    void broadcastsToEverySubscriber() {
        List<AgentEvent> manager = new ArrayList<>();
        List<AgentEvent> wsBridge = new ArrayList<>();
        bus.subscribe(manager::add);
        bus.subscribe(wsBridge::add);

        AgentEvent event = AgentEvent.started(COMMAND, "researcher");
        bus.publish(event);

        assertThat(manager).containsExactly(event);
        assertThat(wsBridge).containsExactly(event);
    }

    @Test
    void oneBadSubscriberNeverStarvesTheOthers() {
        List<AgentEvent> healthy = new ArrayList<>();
        bus.subscribe(e -> {
            throw new IllegalStateException("bad listener");
        });
        bus.subscribe(healthy::add);

        bus.publish(AgentEvent.started(COMMAND, "researcher"));

        // The Manager must still complete its delegation even if the WS bridge blows up.
        assertThat(healthy).hasSize(1);
    }
}
