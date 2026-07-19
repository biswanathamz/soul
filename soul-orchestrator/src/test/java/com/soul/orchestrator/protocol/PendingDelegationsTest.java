package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** The Manager's await: correlation, honest failure, and timeout-issues-a-cancel. */
class PendingDelegationsTest {

    private final InProcessCommandBus commands = new InProcessCommandBus();
    private final InProcessEventBus events = new InProcessEventBus();
    private final PendingDelegations pending = new PendingDelegations(commands, events);
    private final List<AgentCommand> seen = new CopyOnWriteArrayList<>();

    private static AgentCommand task() {
        return AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "latest Node.js LTS"));
    }

    @Test
    void completesWithTheWorkersResult() {
        commands.register("researcher", cmd -> events.publish(AgentEvent.completed(
                cmd, "researcher", new TaskResult(0.94, "Node.js 22 'Jod' is the current LTS.", Map.of()))));

        AgentEvent outcome = pending.dispatchAndAwait(task(), Duration.ofSeconds(2));

        assertThat(outcome.type()).isEqualTo(AgentEvent.COMPLETED);
        assertThat(outcome.result().confidence()).isEqualTo(0.94);
        assertThat(outcome.result().summary()).contains("Jod");
    }

    @Test
    void onlyTheMatchingCommandsEventResolvesTheAwait() {
        // Correlation is by command id — a stray worker event must not complete this one.
        commands.register("researcher", cmd -> {
            events.publish(AgentEvent.completed(
                    AgentCommand.task("super", "researcher", "conv-1", Map.of("task", "someone else's")),
                    "researcher", new TaskResult(1.0, "someone else's answer", Map.of())));
            events.publish(AgentEvent.progress(cmd, "researcher", "searching", "Searching the web…"));
            events.publish(AgentEvent.completed(cmd, "researcher", new TaskResult(0.8, "mine", Map.of())));
        });

        AgentEvent outcome = pending.dispatchAndAwait(task(), Duration.ofSeconds(2));

        assertThat(outcome.result().summary()).isEqualTo("mine");
    }

    @Test
    void timeoutIssuesACancelSoTheWorkerActuallyStops() throws Exception {
        CountDownLatch cancelDelivered = new CountDownLatch(1);
        commands.register("researcher", cmd -> { // never emits a terminal event
            seen.add(cmd);
            if (cmd.isCancel()) {
                cancelDelivered.countDown();
            }
        });
        AgentCommand task = task();

        AgentEvent outcome = pending.dispatchAndAwait(task, Duration.ofMillis(150));

        assertThat(outcome.type()).isEqualTo(AgentEvent.FAILED);
        assertThat(outcome.reason()).contains("timed out");
        assertThat(cancelDelivered.await(2, SECONDS)).isTrue();
        assertThat(seen).anyMatch(c -> c.isCancel() && task.id().equals(c.cancelTarget()));
    }

    @Test
    void aCancelledTaskResolvesTheAwaitAsCancelled() {
        commands.register("researcher", cmd -> events.publish(AgentEvent.cancelled(cmd, "researcher")));

        AgentEvent outcome = pending.dispatchAndAwait(task(), Duration.ofSeconds(2));

        assertThat(outcome.type()).isEqualTo(AgentEvent.CANCELLED);
    }

    @Test
    void anUnroutableCommandComesBackAsFailedNotAnException() {
        // The delegate tool always needs something honest to hand the model.
        AgentEvent outcome = pending.dispatchAndAwait(
                AgentCommand.task("super", "nobody", "conv-1", Map.of("task", "x")), Duration.ofSeconds(2));

        assertThat(outcome.type()).isEqualTo(AgentEvent.FAILED);
        assertThat(outcome.reason()).contains("no worker registered");
    }

    @Test
    void stopCancelsWhateverTheConversationHasInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelDelivered = new CountDownLatch(1);
        commands.register("researcher", cmd -> {
            seen.add(cmd);
            if (cmd.isCancel()) {
                cancelDelivered.countDown();
                return;
            }
            started.countDown();
        });
        AgentCommand task = task();

        // Delegate on another thread — the Manager blocks in its loop while research runs.
        Thread manager = new Thread(() -> pending.dispatchAndAwait(task, Duration.ofSeconds(5)));
        manager.start();
        assertThat(started.await(2, SECONDS)).isTrue();

        assertThat(pending.cancelConversation("conv-1")).isTrue();

        assertThat(cancelDelivered.await(2, SECONDS)).isTrue();
        assertThat(seen).anyMatch(c -> c.isCancel() && task.id().equals(c.cancelTarget()));
        events.publish(AgentEvent.cancelled(task, "researcher")); // let the Manager unblock
        manager.join(2000);
    }

    @Test
    void stoppingWithNothingInFlightIsAHarmlessNoOp() {
        assertThat(pending.cancelConversation("conv-1")).isFalse();
        assertThat(pending.isActive("conv-1")).isFalse();
    }
}
