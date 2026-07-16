package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Routing, isolation, and the control-plane property cancellation depends on. */
class InProcessCommandBusTest {

    private final InProcessCommandBus bus = new InProcessCommandBus();

    private static AgentCommand task(String target) {
        return AgentCommand.task("super", target, "conv-1", Map.of("task", "latest Node.js LTS"));
    }

    @Test
    void routesEachCommandToItsTargetOnly() throws Exception {
        List<AgentCommand> researcher = new CopyOnWriteArrayList<>();
        List<AgentCommand> calendar = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        bus.register("researcher", cmd -> {
            researcher.add(cmd);
            delivered.countDown();
        });
        bus.register("calendar", calendar::add);

        bus.send(task("researcher"));

        assertThat(delivered.await(2, SECONDS)).isTrue();
        assertThat(researcher).hasSize(1);
        assertThat(calendar).isEmpty(); // target isolation — no broadcast semantics here
    }

    @Test
    void unknownTargetIsRejectedRatherThanSilentlyDropped() {
        assertThatThrownBy(() -> bus.send(task("nobody")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no worker registered for target: nobody");
    }

    @Test
    void cancelPreemptsTheTaskItStopsInsteadOfQueueingBehindIt() throws Exception {
        // THE property that makes §3.5 work: dispatched on the worker's single thread, a
        // cancel would wait for the very task it cancels to finish — arriving uselessly late.
        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch cancelSeen = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        bus.register("researcher", cmd -> {
            if (cmd.isCancel()) {
                cancelSeen.countDown();
                return;
            }
            taskRunning.countDown();
            try {
                releaseTask.await(2, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        bus.send(task("researcher"));
        assertThat(taskRunning.await(2, SECONDS)).isTrue(); // task is mid-flight, thread busy

        bus.send(AgentCommand.cancel("super", "researcher", "conv-1", UUID.randomUUID()));

        assertThat(cancelSeen.await(2, SECONDS)).isTrue();
        releaseTask.countDown();
    }

    @Test
    void aSlowWorkerNeverBlocksAnother() throws Exception {
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        bus.register("researcher", cmd -> {
            try {
                releaseSlow.await(2, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        bus.register("calendar", cmd -> fastDone.countDown());

        bus.send(task("researcher"));
        bus.send(task("calendar"));

        assertThat(fastDone.await(2, SECONDS)).isTrue(); // one pool per worker, so no head-of-line block
        releaseSlow.countDown();
    }

    @Test
    void aWorkerThatThrowsDoesNotPoisonItsQueue() throws Exception {
        CountDownLatch second = new CountDownLatch(1);
        List<AgentCommand> seen = new CopyOnWriteArrayList<>();
        bus.register("researcher", cmd -> {
            seen.add(cmd);
            if (seen.size() == 1) {
                throw new IllegalStateException("boom");
            }
            second.countDown();
        });

        bus.send(task("researcher"));
        bus.send(task("researcher"));

        assertThat(second.await(2, SECONDS)).isTrue(); // executor thread survived the throw
        assertThat(seen).hasSize(2);
    }
}
