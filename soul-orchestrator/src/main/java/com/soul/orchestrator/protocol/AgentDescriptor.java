package com.soul.orchestrator.protocol;

import java.util.Objects;
import java.util.Set;

/**
 * What an agent advertises to the fleet (docs/researcher-agent.md §3.4): its name, a
 * one-line description (the {@code delegate} tool's generated text quotes it), and the
 * namespaced {@code domain.action} capabilities it provides.
 *
 * <p><b>Capabilities are not skills.</b> Capabilities are the agent's public contract —
 * what others may ask of it. Skills (the pool) are its private toolbox for fulfilling
 * them: the researcher advertises {@code research.search} and fulfills it internally
 * with the {@code web-search} skill.
 */
public record AgentDescriptor(String name, String description, Set<String> capabilities) {

    public AgentDescriptor {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
