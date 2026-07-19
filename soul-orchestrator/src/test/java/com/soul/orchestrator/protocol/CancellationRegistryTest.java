package com.soul.orchestrator.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancellationRegistryTest {

    private final CancellationRegistry cancellation = new CancellationRegistry();

    @Test
    void raisesTheFlagTheAgentLoopChecks() {
        UUID command = UUID.randomUUID();
        cancellation.begin(command);
        assertThat(cancellation.isCancelled(command)).isFalse();

        cancellation.cancel(command);

        assertThat(cancellation.isCancelled(command)).isTrue();
    }

    @Test
    void aCancelThatRacesAheadOfTheWorkerIsNotSwallowed() {
        // "Stop" can land while the task is still queued on the worker's executor. begin()
        // must not overwrite the flag back to active, or the fast stop would be lost.
        UUID command = UUID.randomUUID();
        cancellation.cancel(command);

        cancellation.begin(command);

        assertThat(cancellation.isCancelled(command)).isTrue();
    }

    @Test
    void anUnknownCommandIsNotCancelled() {
        assertThat(cancellation.isCancelled(UUID.randomUUID())).isFalse();
    }

    @Test
    void endClearsTheFlagSoIdsDoNotAccumulate() {
        UUID command = UUID.randomUUID();
        cancellation.begin(command);
        cancellation.cancel(command);

        cancellation.end(command);

        assertThat(cancellation.isCancelled(command)).isFalse();
    }
}
