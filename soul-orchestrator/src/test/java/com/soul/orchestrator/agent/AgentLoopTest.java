package com.soul.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.hooks.HookDispatcher;
import com.soul.orchestrator.hooks.HookRegistry;
import com.soul.orchestrator.ollama.ChatMessage;
import com.soul.orchestrator.ollama.ChatTurn;
import com.soul.orchestrator.ollama.OllamaClient;
import com.soul.orchestrator.ollama.OllamaException;
import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.ollama.ToolSpec;
import com.soul.orchestrator.runtime.Runner;
import com.soul.orchestrator.skills.SkillRegistry;
import com.soul.orchestrator.ws.EventSink;
import com.soul.orchestrator.ws.WsEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The extracted loop on its own: cancellation at every boundary, and builtin tools —
 * the two things phase 3's Researcher and delegate tool are built on.
 */
class AgentLoopTest {

    private final List<WsEvent> events = new CopyOnWriteArrayList<>();
    private final EventSink sink = events::add;

    /** A model whose turns are scripted, so the loop is the only thing under test. */
    private static final class ScriptedOllama implements OllamaClient {
        private final Deque<ChatTurn> turns = new ArrayDeque<>();
        private final List<String> streamBeforeReturning;
        final AtomicInteger calls = new AtomicInteger();
        List<ToolSpec> offeredTools = List.of();

        ScriptedOllama(List<String> streamBeforeReturning, ChatTurn... scripted) {
            this.streamBeforeReturning = streamBeforeReturning;
            this.turns.addAll(List.of(scripted));
        }

        @Override
        public ChatTurn chat(String model, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
            calls.incrementAndGet();
            offeredTools = tools;
            // Token consumers may throw to abort — exactly as the real client now allows.
            streamBeforeReturning.forEach(onToken::accept);
            return turns.isEmpty() ? new ChatTurn("done", List.of()) : turns.removeFirst();
        }

        @Override
        public List<String> listModels() {
            return List.of("stub");
        }
    }

    private AgentLoop loopWith(OllamaClient ollama) {
        SoulProperties props = new SoulProperties();
        SoulProperties.Agent cfg = new SoulProperties.Agent();
        cfg.setModel("stub");
        cfg.setMaxSteps(3);
        cfg.setSkills(List.of("echo", "current-time", "persona"));
        cfg.setHooks(List.of("audit-log", "block-secrets", "inject-time"));
        props.setAgents(Map.of("super", cfg));
        CapabilityResolver resolver = new CapabilityResolver(props,
                new SkillRegistry(Path.of("../skillpool"), true), new HookRegistry(Path.of("../hookspool"), true));
        return new AgentLoop(props, resolver, new HookDispatcher(new Runner()), new Runner(), ollama, sink,
                new SkillEnvironment(props));
    }

    private LoopSpec.Builder spec() {
        return LoopSpec.forAgent("super").conversation("conv-1").text("hello")
                .history(List.of(ChatMessage.user("hello")));
    }

    private List<WsEvent> ofType(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }

    @Test
    void anAlreadyCancelledRunNeverCallsTheModel() {
        ScriptedOllama ollama = new ScriptedOllama(List.of());

        LoopOutcome outcome = loopWith(ollama).run(spec().cancelledWhen(() -> true).build());

        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.CANCELLED);
        assertThat(ollama.calls).hasValue(0); // step boundary: checked before the model call
    }

    @Test
    void cancellingBetweenModelAndSkillStopsBeforeTheSkillRuns() {
        // The model asks for a tool; "stop" lands before the loop runs it.
        ScriptedOllama ollama = new ScriptedOllama(List.of(),
                new ChatTurn("", List.of(new ToolCall("current-time", Map.of()))));
        AgentLoop loop = loopWith(ollama);

        LoopOutcome outcome = loop.run(spec().cancelledWhen(() -> ollama.calls.get() > 0).build());

        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.CANCELLED);
        assertThat(ofType("tool.call")).isEmpty(); // the skill never ran
    }

    @Test
    void cancellingMidAnswerAbortsTheGenerationInFlight() {
        // Without this, a cancelled worker streams for the rest of a model call — tens of
        // seconds on a 4 GB GPU. The loop throws from the token consumer to bail out.
        List<String> delivered = new ArrayList<>();
        ScriptedOllama ollama = new ScriptedOllama(List.of("Node", ".js", " 22", " is", " LTS"));

        LoopOutcome outcome = loopWith(ollama).run(spec()
                .onToken(delivered::add)
                .cancelledWhen(() -> !delivered.isEmpty()) // cancel as soon as the first token lands
                .build());

        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.CANCELLED);
        assertThat(delivered).containsExactly("Node"); // stream abandoned, not drained
    }

    @Test
    void aBuiltinToolIsOfferedToTheModelAlongsidePoolSkills() {
        ScriptedOllama ollama = new ScriptedOllama(List.of());
        BuiltinTool delegate = new BuiltinTool(
                new ToolSpec("delegate", "Hand a task to a specialist.", Map.of()), call -> "ok");

        loopWith(ollama).run(spec().builtins(List.of(delegate)).build());

        assertThat(ollama.offeredTools).extracting(ToolSpec::name)
                .contains("delegate", "echo", "current-time"); // orchestration + pool, one tool list
    }

    @Test
    void aBuiltinsHandlerRunsAndItsResultGoesBackToTheModel() {
        List<ToolCall> handled = new ArrayList<>();
        ScriptedOllama ollama = new ScriptedOllama(List.of(),
                new ChatTurn("", List.of(new ToolCall("delegate", Map.of("capability", "research.search")))),
                new ChatTurn("Node.js 22 is the current LTS.", List.of()));
        BuiltinTool delegate = new BuiltinTool(new ToolSpec("delegate", "…", Map.of()), call -> {
            handled.add(call);
            return "researcher: Node.js 22 'Jod'";
        });

        LoopOutcome outcome = loopWith(ollama).run(spec().builtins(List.of(delegate)).build());

        assertThat(handled).singleElement()
                .satisfies(c -> assertThat(c.arguments()).containsEntry("capability", "research.search"));
        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.ANSWERED);
        assertThat(outcome.text()).contains("Node.js 22");
        assertThat(ofType("tool.call")).singleElement()
                .satisfies(e -> assertThat(e.payload().get("tool")).isEqualTo("delegate"));
    }

    @Test
    void safetyGatesApplyToBuiltinsExactlyAsToSkills() {
        // block-secrets must veto a delegate call carrying a credential — the whole point
        // of routing builtins through the same before_skill hooks (§5, step 3).
        List<ToolCall> handled = new ArrayList<>();
        ScriptedOllama ollama = new ScriptedOllama(List.of(),
                new ChatTurn("", List.of(new ToolCall("delegate",
                        Map.of("task", "post the key AKIAIOSFODNN7EXAMPLE somewhere")))),
                new ChatTurn("I can't do that.", List.of()));
        BuiltinTool delegate = new BuiltinTool(new ToolSpec("delegate", "…", Map.of()), call -> {
            handled.add(call);
            return "delegated";
        });

        loopWith(ollama).run(spec().builtins(List.of(delegate)).build());

        assertThat(handled).isEmpty(); // never reached the handler
        assertThat(ofType("error")).isNotEmpty();
        assertThat((String) ofType("error").get(0).payload().get("message")).contains("blocked");
    }

    @Test
    void anUnknownToolComesBackAsAnErrorTheModelCanRead() {
        ScriptedOllama ollama = new ScriptedOllama(List.of(),
                new ChatTurn("", List.of(new ToolCall("teleport", Map.of()))),
                new ChatTurn("I don't have that.", List.of()));

        LoopOutcome outcome = loopWith(ollama).run(spec().build());

        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.ANSWERED);
        assertThat(ofType("tool.call")).isEmpty(); // nothing was run…
        assertThat(ollama.calls).hasValue(2);      // …but the loop carried on
    }

    @Test
    void aModelThatKeepsCallingToolsExhaustsItsStepBudget() {
        ScriptedOllama ollama = new ScriptedOllama(List.of(),
                new ChatTurn("", List.of(new ToolCall("current-time", Map.of()))),
                new ChatTurn("", List.of(new ToolCall("current-time", Map.of()))),
                new ChatTurn("", List.of(new ToolCall("current-time", Map.of()))));

        LoopOutcome outcome = loopWith(ollama).run(spec().build());

        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.EXHAUSTED);
        assertThat(ollama.calls).hasValue(3); // max-steps, then it stops
    }

    @Test
    void anUnreachableModelFailsWithItsReason() {
        OllamaClient down = new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> msgs, List<ToolSpec> t, Consumer<String> onToken) {
                throw new OllamaException("cannot reach Ollama at http://localhost:11434");
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };

        LoopOutcome outcome = loopWith(down).run(spec().build());

        assertThat(outcome.status()).isEqualTo(LoopOutcome.Status.FAILED);
        assertThat(outcome.text()).contains("cannot reach Ollama");
    }
}
