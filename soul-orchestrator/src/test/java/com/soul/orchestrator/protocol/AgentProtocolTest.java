package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The two data models: commands say "do this", events say "this happened". */
class AgentProtocolTest {

    @Test
    void taskAndCancelAreTheTwoImperatives() {
        AgentCommand task = AgentCommand.task("super", "researcher", "conv-1",
                Map.of("task", "latest Node.js LTS", "capability", "research.search"));

        assertThat(task.isTask()).isTrue();
        assertThat(task.isCancel()).isFalse();
        assertThat(task.capability()).isEqualTo("research.search");
        assertThat(task.target()).isEqualTo("researcher");
    }

    @Test
    void aCancelNamesTheTaskItStops() {
        UUID inFlight = UUID.randomUUID();

        AgentCommand cancel = AgentCommand.cancel("super", "researcher", "conv-1", inFlight);

        assertThat(cancel.isCancel()).isTrue();
        assertThat(cancel.cancelTarget()).isEqualTo(inFlight);
        assertThat(cancel.id()).isNotEqualTo(inFlight); // its own identity; it REFERENCES the task
    }

    @Test
    void commandPayloadsAreImmutableOnceIssued() {
        Map<String, Object> mutable = new HashMap<>(Map.of("task", "x"));
        AgentCommand command = AgentCommand.task("super", "researcher", "conv-1", mutable);

        mutable.put("task", "tampered");

        assertThat(command.payload()).containsEntry("task", "x");
        assertThatThrownBy(() -> command.payload().put("task", "tampered"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyTerminalEventsResolveADelegation() {
        AgentCommand command = AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "x"));

        assertThat(AgentEvent.started(command, "researcher").isTerminal()).isFalse();
        assertThat(AgentEvent.progress(command, "researcher", "searching", "Searching…").isTerminal()).isFalse();
        assertThat(AgentEvent.completed(command, "researcher", new TaskResult(0.9, "s", Map.of())).isTerminal())
                .isTrue();
        assertThat(AgentEvent.failed(command, "researcher", "network down").isTerminal()).isTrue();
        assertThat(AgentEvent.cancelled(command, "researcher").isTerminal()).isTrue();
    }

    @Test
    void everyEventCarriesTheConversationItBelongsTo() {
        // The WS bridge routes on this — an event that needed a lookup to find its
        // conversation would race the Manager clearing the delegation it belongs to.
        AgentCommand command = AgentCommand.task("super", "researcher", "conv-42", Map.of("task", "x"));

        assertThat(AgentEvent.started(command, "researcher").conversationId()).isEqualTo("conv-42");
        assertThat(AgentEvent.cancelled(command, "researcher").conversationId()).isEqualTo("conv-42");
    }

    @Test
    void progressNarratesAStageWithAReadyToRenderLabel() {
        AgentCommand command = AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "x"));
        AgentEvent event = AgentEvent.progress(command, "researcher",
                "reading", "Reading nodejs.org (1/3)", 1, 3);

        assertThat(event.type()).isEqualTo(AgentEvent.PROGRESS);
        assertThat(event.payload())
                .containsEntry("stage", "reading")          // machine key
                .containsEntry("label", "Reading nodejs.org (1/3)") // what the UI shows verbatim
                .containsEntry("step", 1)
                .containsEntry("total", 3);
    }

    @Test
    void progressOmitsStepAndTotalWhenTheyDoNotApply() {
        AgentCommand command = AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "x"));

        AgentEvent event = AgentEvent.progress(command, "researcher", "searching", "Searching the web…");

        assertThat(event.payload()).containsOnlyKeys("stage", "label");
    }

    @Test
    void everyEventCarriesTheCommandItIsAbout() {
        AgentCommand command = AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "x"));

        AgentEvent event = AgentEvent.failed(command, "researcher", "network down");

        assertThat(event.commandId()).isEqualTo(command.id());
        assertThat(event.reason()).isEqualTo("network down");
        assertThat(event.agent()).isEqualTo("researcher");
    }
}
