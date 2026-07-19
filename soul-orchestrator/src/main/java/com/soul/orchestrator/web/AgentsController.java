package com.soul.orchestrator.web;

import com.soul.orchestrator.agent.AgentCapabilities;
import com.soul.orchestrator.agent.CapabilityResolver;
import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.hooks.HookManifest;
import com.soul.orchestrator.skills.SkillManifest;
import com.soul.orchestrator.web.Dtos.AgentDto;
import com.soul.orchestrator.web.Dtos.RebindRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Agent roster + per-agent model rebinding (SPEC §5.1). */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentsController {

    private final SoulProperties props;
    private final CapabilityResolver resolver;

    public AgentsController(SoulProperties props, CapabilityResolver resolver) {
        this.props = props;
        this.resolver = resolver;
    }

    @GetMapping
    public List<AgentDto> agents() {
        return props.getAgents().keySet().stream().map(this::toDto).toList();
    }

    @PutMapping("/{role}/model")
    public AgentDto rebind(@PathVariable String role, @RequestBody RebindRequest request) {
        SoulProperties.Agent agent = props.getAgents().get(role);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown agent: " + role);
        }
        if (request == null || request.model() == null || request.model().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is required");
        }
        agent.setModel(request.model());
        return toDto(role);
    }

    private AgentDto toDto(String role) {
        SoulProperties.Agent cfg = props.getAgents().get(role);
        AgentCapabilities caps = resolver.resolve(role);
        return new AgentDto(
                role,
                cfg.getModel(),
                "idle",
                describe(role, cfg),
                caps.skills().stream().map(SkillManifest::name).toList(),
                caps.hooks().stream().map(HookManifest::name).toList());
    }

    private static String describe(String role, SoulProperties.Agent cfg) {
        if ("super".equals(role)) {
            return "Manager — plans, delegates, runs skills & hooks";
        }
        // Workers describe themselves in config — the same line the delegate tool quotes.
        return cfg.getDescription().isBlank() ? role : cfg.getDescription().strip();
    }
}
