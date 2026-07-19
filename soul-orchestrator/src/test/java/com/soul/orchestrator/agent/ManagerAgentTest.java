package com.soul.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.conversation.ConversationStore;
import com.soul.orchestrator.hooks.HookDispatcher;
import com.soul.orchestrator.hooks.HookRegistry;
import com.soul.orchestrator.ollama.ChatMessage;
import com.soul.orchestrator.ollama.ChatTurn;
import com.soul.orchestrator.ollama.OllamaClient;
import com.soul.orchestrator.ollama.StubOllamaClient;
import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.ollama.ToolSpec;
import com.soul.orchestrator.protocol.InProcessAgentRegistry;
import com.soul.orchestrator.protocol.InProcessCommandBus;
import com.soul.orchestrator.protocol.InProcessEventBus;
import com.soul.orchestrator.protocol.PendingDelegations;
import com.soul.orchestrator.runtime.Runner;
import com.soul.orchestrator.skills.SkillRegistry;
import com.soul.orchestrator.ws.EventSink;
import com.soul.orchestrator.ws.WsEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Drives the whole Manager loop against the REAL skill/hook pools, with only the LLM
 * stubbed — the exact "What is the current time?" flow, plus a safety-gate block.
 */
class ManagerAgentTest {

    private final List<WsEvent> events = new CopyOnWriteArrayList<>();
    private final EventSink sink = events::add;
    private final ConversationStore store = new ConversationStore();

    private ManagerAgent agentWith(OllamaClient ollama) {
        SkillRegistry skills = new SkillRegistry(Path.of("../skillpool"), true);
        HookRegistry hooks = new HookRegistry(Path.of("../hookspool"), true);
        SoulProperties props = new SoulProperties();
        SoulProperties.Agent cfg = new SoulProperties.Agent();
        cfg.setModel("stub");
        cfg.setSkills(List.of("echo", "current-time", "persona"));
        cfg.setHooks(List.of("audit-log", "block-secrets", "inject-time"));
        props.setAgents(Map.of("super", cfg));
        CapabilityResolver resolver = new CapabilityResolver(props, skills, hooks);
        HookDispatcher dispatcher = new HookDispatcher(new Runner());
        AgentLoop loop = new AgentLoop(props, resolver, dispatcher, new Runner(), ollama, sink,
                new SkillEnvironment(props));
        // No worker registers here, so the Manager is offered no delegate tool at all —
        // exactly the pre-researcher behaviour these tests pin down.
        InProcessCommandBus bus = new InProcessCommandBus();
        DelegateTool delegate = new DelegateTool(new InProcessAgentRegistry(bus),
                new PendingDelegations(bus, new InProcessEventBus()), props, sink);
        return new ManagerAgent(resolver, dispatcher, loop, delegate, store, sink);
    }

    private List<WsEvent> ofType(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }

    @Test
    void currentTimeFlowRunsSkillAndAnswers() {
        ManagerAgent agent = agentWith(new StubOllamaClient());
        String conv = store.ensure(null);
        store.append(conv, "user", "What is the current time?");

        agent.handle(conv, "msg-1", "What is the current time?");

        // The Manager delegated to the current-time skill…
        assertThat(ofType("tool.call")).singleElement()
                .satisfies(e -> assertThat(e.payload().get("tool")).isEqualTo("current-time"));
        assertThat(ofType("tool.result")).hasSize(1);
        // …streamed tokens, and committed a final answer containing the real time.
        assertThat(ofType("token")).isNotEmpty();
        assertThat(ofType("task.done")).singleElement().satisfies(e -> {
            String text = (String) e.payload().get("text");
            assertThat(text).startsWith("The current time is").contains("2026");
        });
        // Ends idle, with the answer persisted.
        assertThat(events.get(events.size() - 1).type()).isEqualTo("agent.status");
        assertThat(store.history(conv).orElseThrow())
                .anyMatch(m -> m.role().equals("assistant") && m.text().contains("current time"));
    }

    @Test
    void blockSecretsVetoesASkillCallWithCredentials() {
        // A model that asks to echo a credential, then answers once it gets the (refused) result.
        OllamaClient sneaky = new OllamaClient() {
            @Override
            public ChatTurn chat(String model, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
                boolean toolDone = messages.stream().anyMatch(m -> "tool".equals(m.role()));
                if (!toolDone) {
                    return new ChatTurn("", List.of(
                            new ToolCall("echo", Map.of("text", "the key is AKIAIOSFODNN7EXAMPLE"))));
                }
                String msg = "I can't process that — it looked like a credential.";
                onToken.accept(msg);
                return new ChatTurn(msg, List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of("stub");
            }
        };
        ManagerAgent agent = agentWith(sneaky);
        String conv = store.ensure(null);
        store.append(conv, "user", "echo my key");

        agent.handle(conv, "msg-2", "echo my key");

        // The safety gate blocked the skill and surfaced an error…
        assertThat(ofType("error")).isNotEmpty();
        assertThat((String) ofType("error").get(0).payload().get("message")).contains("blocked");
        // …no echo skill actually ran (no tool.result), but the turn still completed.
        assertThat(ofType("tool.result")).isEmpty();
        assertThat(ofType("task.done")).hasSize(1);
    }
}
