package com.soul.orchestrator.protocol;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The capability index (docs/researcher-agent.md §3.4). Registration wires the worker to
 * the command bus in the same call, so an agent cannot advertise a capability it has no
 * worker to serve.
 */
@Component
public class InProcessAgentRegistry implements AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(InProcessAgentRegistry.class);

    private final CommandBus commands;
    private final Map<String, AgentDescriptor> byName = new ConcurrentHashMap<>();
    /** capability → providing agent name. Sorted so generated tool descriptions are stable. */
    private final Map<String, String> providers = new TreeMap<>();

    public InProcessAgentRegistry(CommandBus commands) {
        this.commands = commands;
    }

    @Override
    public synchronized void register(AgentDescriptor descriptor, Consumer<AgentCommand> worker) {
        byName.put(descriptor.name(), descriptor);
        for (String capability : descriptor.capabilities()) {
            String incumbent = providers.putIfAbsent(capability, descriptor.name());
            if (incumbent != null && !incumbent.equals(descriptor.name())) {
                // v1: first registration wins. A priority field enables real failover later.
                log.warn("capability '{}' is already provided by '{}' — ignoring provider '{}'",
                        capability, incumbent, descriptor.name());
            }
        }
        commands.register(descriptor.name(), worker);
        log.info("registry: '{}' registered with capabilities {}", descriptor.name(), descriptor.capabilities());
    }

    @Override
    public synchronized Optional<AgentDescriptor> whoSupports(String capability) {
        return Optional.ofNullable(providers.get(capability)).map(byName::get);
    }

    @Override
    public synchronized Set<String> capabilities() {
        // Insertion-ordered copy of a sorted map: the delegate tool's generated enum and
        // description must not reshuffle between runs.
        return Collections.unmodifiableSet(new LinkedHashSet<>(providers.keySet()));
    }

    @Override
    public synchronized List<AgentDescriptor> available() {
        return List.copyOf(byName.values());
    }
}
