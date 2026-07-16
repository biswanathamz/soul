package com.soul.orchestrator.protocol;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Manager's side of a delegation (docs/researcher-agent.md §3.3): send a task
 * command, block until its terminal event arrives, correlated by command id.
 *
 * <p>Written once against the generic {@code task.*} lifecycle — every future worker
 * (calendar, email, browser) delegates through this exact code without a change.
 */
@Component
public class PendingDelegations {

    private static final Logger log = LoggerFactory.getLogger(PendingDelegations.class);

    private final CommandBus commands;
    private final Map<UUID, CompletableFuture<AgentEvent>> pending = new ConcurrentHashMap<>();
    /** The in-flight task per conversation — what the user's "stop" resolves to (§3.5). */
    private final Map<String, AgentCommand> activeByConversation = new ConcurrentHashMap<>();

    public PendingDelegations(CommandBus commands, EventBus events) {
        this.commands = commands;
        events.subscribe(this::onEvent);
    }

    private void onEvent(AgentEvent event) {
        if (!event.isTerminal()) {
            return; // started/progress narrate; only terminals resolve the await
        }
        CompletableFuture<AgentEvent> future = pending.remove(event.commandId());
        if (future != null) {
            future.complete(event);
        }
    }

    /**
     * Dispatch {@code command} and wait for {@code task.completed} / {@code task.failed} /
     * {@code task.cancelled}. Always returns an event — failures and timeouts come back as
     * {@code task.failed} rather than exceptions, so the delegate tool always has something
     * honest to hand the model.
     */
    public AgentEvent dispatchAndAwait(AgentCommand command, Duration timeout) {
        CompletableFuture<AgentEvent> future = new CompletableFuture<>();
        pending.put(command.id(), future);
        if (command.conversationId() != null) {
            activeByConversation.put(command.conversationId(), command);
        }
        try {
            commands.send(command);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // A timed-out worker must actually STOP, not keep burning CPU behind an
            // abandoned future. Same mechanism as the user's stop button — no special case.
            sendCancel(command);
            return AgentEvent.failed(command, command.target(),
                    "timed out after " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendCancel(command);
            return AgentEvent.failed(command, command.target(), "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return AgentEvent.failed(command, command.target(), String.valueOf(cause.getMessage()));
        } catch (RuntimeException e) {
            // send() rejected it — e.g. no worker registered for the target.
            return AgentEvent.failed(command, command.target(), e.getMessage());
        } finally {
            pending.remove(command.id());
            if (command.conversationId() != null) {
                activeByConversation.remove(command.conversationId(), command);
            }
        }
    }

    /**
     * Cancel whatever this conversation has in flight — the stop button, a voice "stop".
     * Returns false when there is nothing to cancel (a harmless no-op, §3.5).
     */
    public boolean cancelConversation(String conversationId) {
        AgentCommand active = activeByConversation.get(conversationId);
        if (active == null) {
            return false;
        }
        sendCancel(active);
        return true;
    }

    /** Whether a delegation is currently in flight for this conversation. */
    public boolean isActive(String conversationId) {
        return activeByConversation.containsKey(conversationId);
    }

    private void sendCancel(AgentCommand task) {
        try {
            commands.send(AgentCommand.cancel(task.issuedBy(), task.target(), task.conversationId(), task.id()));
        } catch (RuntimeException e) {
            log.warn("cancel for command {} could not be delivered: {}", task.id(), e.getMessage());
        }
    }
}
