package com.soul.orchestrator.protocol;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes commands to their target's worker, each on its own single-thread executor —
 * a slow researcher never blocks the Manager's loop, and one worker's queue never
 * interleaves with another's (docs/researcher-agent.md §3.3).
 */
@Component
public class InProcessCommandBus implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessCommandBus.class);

    private final Map<String, Consumer<AgentCommand>> workers = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

    @Override
    public void register(String target, Consumer<AgentCommand> worker) {
        workers.put(target, worker);
        executors.computeIfAbsent(target, name -> Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name + "-worker");
            t.setDaemon(true);
            return t;
        }));
        log.info("command bus: worker registered for target '{}'", target);
    }

    @Override
    public void send(AgentCommand command) {
        Consumer<AgentCommand> worker = workers.get(command.target());
        if (worker == null) {
            throw new IllegalArgumentException("no worker registered for target: " + command.target());
        }

        // Cancels are control plane: they must PREEMPT the queue, never join it. A cancel
        // dispatched on the worker's single thread would sit behind the very task it is
        // meant to stop and only arrive once that task finished — useless. Handling a
        // cancel is a cheap flag-set (CancellationRegistry), so running it on the caller's
        // thread is safe. Tasks are data plane: queued, sequential, isolated per worker.
        if (command.isCancel()) {
            deliver(worker, command);
            return;
        }
        executors.get(command.target()).execute(() -> deliver(worker, command));
    }

    private void deliver(Consumer<AgentCommand> worker, AgentCommand command) {
        try {
            worker.accept(command);
        } catch (RuntimeException e) {
            // A worker that throws must not poison the bus or kill its executor thread.
            log.error("worker '{}' threw handling {} command {}", command.target(), command.type(), command.id(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        executors.values().forEach(ExecutorService::shutdownNow);
    }
}
