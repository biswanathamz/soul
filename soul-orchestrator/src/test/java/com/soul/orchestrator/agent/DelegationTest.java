package com.soul.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.conversation.ConversationStore;
import com.soul.orchestrator.hooks.HookDispatcher;
import com.soul.orchestrator.hooks.HookRegistry;
import com.soul.orchestrator.ollama.ChatMessage;
import com.soul.orchestrator.ollama.ChatTurn;
import com.soul.orchestrator.ollama.OllamaClient;
import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.ollama.ToolSpec;
import com.soul.orchestrator.protocol.AgentEvent;
import com.soul.orchestrator.protocol.CancellationRegistry;
import com.soul.orchestrator.protocol.InProcessAgentRegistry;
import com.soul.orchestrator.protocol.InProcessCommandBus;
import com.soul.orchestrator.protocol.InProcessEventBus;
import com.soul.orchestrator.protocol.PendingDelegations;
import com.soul.orchestrator.runtime.Runner;
import com.soul.orchestrator.skills.SkillRegistry;
import com.soul.orchestrator.ws.EventSink;
import com.soul.orchestrator.ws.ProtocolWsBridge;
import com.soul.orchestrator.ws.WsEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The whole delegation feature end to end, with only the two LLMs stubbed: real pools,
 * real skills (against their offline fixtures), real protocol, real buses, real WS bridge.
 *
 * <p>This is phase 3's exit test — the Manager delegating by capability, the Researcher
 * narrating its stages and rating its own findings, the confidence policy retrying, and
 * the user stopping it mid-flight.
 */
class DelegationTest {

    private static final String CONV = "conv-1";

    private final List<WsEvent> ws = new CopyOnWriteArrayList<>();
    private final List<AgentEvent> protocol = new CopyOnWriteArrayList<>();
    private final EventSink sink = ws::add;
    private final ConversationStore store = new ConversationStore();

    private final InProcessCommandBus commands = new InProcessCommandBus();
    private final InProcessEventBus bus = new InProcessEventBus();
    private final InProcessAgentRegistry registry = new InProcessAgentRegistry(commands);
    private final PendingDelegations pending = new PendingDelegations(commands, bus);
    private final CancellationRegistry cancellation = new CancellationRegistry();

    /** Routes to a different scripted model per agent, exactly as per-agent models do. */
    private record Fleet(OllamaClient manager, OllamaClient researcher) implements OllamaClient {
        @Override
        public ChatTurn chat(String model, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
            return "stub-researcher".equals(model)
                    ? researcher.chat(model, messages, tools, onToken)
                    : manager.chat(model, messages, tools, onToken);
        }

        @Override
        public List<String> listModels() {
            return List.of("stub-manager", "stub-researcher");
        }
    }

    private ManagerAgent wire(OllamaClient managerModel, OllamaClient researcherModel) {
        SoulProperties props = new SoulProperties();
        SoulProperties.Agent manager = new SoulProperties.Agent();
        manager.setModel("stub-manager");
        manager.setSkills(List.of("echo", "current-time", "persona"));
        manager.setHooks(List.of("audit-log", "block-secrets", "inject-time"));

        SoulProperties.Agent researcher = new SoulProperties.Agent();
        researcher.setModel("stub-researcher");
        researcher.setMaxSteps(8);
        researcher.setDescription("finds current, real-world information on the web.");
        researcher.setCapabilities(List.of("research.search", "research.fetch"));
        researcher.setSkills(List.of("web-search", "fetch-page", "researcher-persona"));
        researcher.setHooks(List.of("audit-log"));

        Map<String, SoulProperties.Agent> agents = new LinkedHashMap<>();
        agents.put("super", manager);
        agents.put("researcher", researcher);
        props.setAgents(agents);

        CapabilityResolver resolver = new CapabilityResolver(props,
                new SkillRegistry(Path.of("../skillpool"), true),
                new HookRegistry(Path.of("../hookspool"), true));
        HookDispatcher hooks = new HookDispatcher(new Runner());

        // The real web-search / fetch-page skills run, but against their own canned
        // fixtures — the skill code is exercised, the internet is never touched.
        SkillEnvironment offline = new SkillEnvironment(props) {
            @Override
            public Map<String, String> forSkills() {
                Map<String, String> env = new LinkedHashMap<>(super.forSkills());
                env.put("SOUL_SKILL_OFFLINE", "1");
                return env;
            }
        };

        AgentLoop loop = new AgentLoop(props, resolver, hooks, new Runner(),
                new Fleet(managerModel, researcherModel), sink, offline);

        bus.subscribe(protocol::add);
        new ProtocolWsBridge(bus, sink);
        new ResearcherWorker(props, loop, registry, bus, cancellation).register();

        DelegateTool delegate = new DelegateTool(registry, pending, props, sink);
        return new ManagerAgent(resolver, hooks, loop, delegate, store, sink);
    }

    // --------------------------------------------------------------------- scripted models

    /** Delegates once, then summarizes whatever comes back. */
    private static OllamaClient managerThatDelegates(String capability) {
        return new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
                if (toolResults(messages).isEmpty()) {
                    return new ChatTurn("", List.of(new ToolCall("delegate",
                            Map.of("capability", capability, "task", "latest Node.js LTS version"))));
                }
                String answer = "Here's what I found: " + toolResults(messages).get(0);
                onToken.accept(answer);
                return new ChatTurn(answer, List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
    }

    /** Searches, reads two pages, then reports findings with a self-rating. */
    private static OllamaClient researcherRating(double confidence) {
        return researcherRating(confidence, confidence, new CountDownLatch(0), new CountDownLatch(0));
    }

    private static OllamaClient researcherRating(double firstAttempt, double retryAttempt,
            CountDownLatch reached, CountDownLatch release) {
        return new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
                reached.countDown();
                try {
                    release.await(5, SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                List<String> results = toolResults(messages);
                if (results.isEmpty()) {
                    return new ChatTurn("", List.of(new ToolCall("web-search",
                            Map.of("query", "latest Node.js LTS version"))));
                }
                if (results.size() == 1) {
                    return new ChatTurn("", List.of(
                            new ToolCall("fetch-page", Map.of("url", "https://nodejs.org/en/about/previous-releases")),
                            new ToolCall("fetch-page", Map.of("url", "https://endoflife.date/nodejs"))));
                }
                // A retry carries the exclude instruction — rate that attempt differently.
                boolean isRetry = messages.stream().anyMatch(msg -> msg.content() != null
                        && msg.content().contains("previous attempt"));
                double rating = isRetry ? retryAttempt : firstAttempt;
                String findings = """
                        FINDINGS:
                        Node.js 22 'Jod' is the current LTS (https://nodejs.org/en/about/previous-releases).

                        SOURCES:
                        - Node.js — https://nodejs.org/en/about/previous-releases
                        - endoflife.date — https://endoflife.date/nodejs

                        AGREEMENT: both sources agree.
                        CONFIDENCE: %s
                        """.formatted(rating);
                return new ChatTurn(findings, List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
    }

    /** Searches, then tries to report straight from the snippets — the real 8B behaviour. */
    private static OllamaClient researcherThatSkipsReading() {
        return new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
                List<String> results = toolResults(messages);
                boolean toldOff = messages.stream().anyMatch(msg -> "user".equals(msg.role())
                        && msg.content() != null && msg.content().contains("not opened a single one"));
                if (results.isEmpty()) {
                    return new ChatTurn("", List.of(new ToolCall("web-search",
                            Map.of("query", "latest Node.js LTS version"))));
                }
                if (!toldOff) {
                    return new ChatTurn(findings(0.9), List.of()); // lazy: snippets are enough, surely
                }
                if (results.size() == 1) {
                    return new ChatTurn("", List.of(
                            new ToolCall("fetch-page", Map.of("url", "https://nodejs.org/en/about/previous-releases")),
                            new ToolCall("fetch-page", Map.of("url", "https://endoflife.date/nodejs"))));
                }
                return new ChatTurn(findings(0.9), List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
    }

    private static String findings(double rating) {
        return """
                FINDINGS:
                Node.js 22 'Jod' is the current LTS (https://nodejs.org/en/about/previous-releases).

                SOURCES:
                - Node.js — https://nodejs.org/en/about/previous-releases
                - endoflife.date — https://endoflife.date/nodejs

                AGREEMENT: both sources agree.
                CONFIDENCE: %s
                """.formatted(rating);
    }

    private static List<String> toolResults(List<ChatMessage> messages) {
        return messages.stream().filter(msg -> "tool".equals(msg.role())).map(ChatMessage::content).toList();
    }

    // --------------------------------------------------------------------- helpers

    private List<WsEvent> ws(String type) {
        return ws.stream().filter(e -> e.type().equals(type)).toList();
    }

    private List<String> stages() {
        return protocol.stream().filter(e -> e.type().equals(AgentEvent.PROGRESS))
                .map(e -> String.valueOf(e.payload().get("stage"))).toList();
    }

    private List<String> labels() {
        return protocol.stream().filter(e -> e.type().equals(AgentEvent.PROGRESS))
                .map(e -> String.valueOf(e.payload().get("label"))).toList();
    }

    // --------------------------------------------------------------------- tests

    @Test
    void aRecencyQuestionIsDelegatedResearchedAndSummarizedBackToTheUser() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"), researcherRating(0.9));
        store.append(CONV, "user", "What's the latest LTS version of Node.js?");

        manager.handle(CONV, "msg-1", "What's the latest LTS version of Node.js?");

        // The Manager handed the task over by CAPABILITY, and the UI was told.
        assertThat(ws("delegation")).singleElement().satisfies(e -> {
            assertThat(e.payload()).containsEntry("from", "super").containsEntry("to", "researcher");
            assertThat(e.payload().get("task")).isEqualTo("latest Node.js LTS version");
        });

        // The Researcher ran its own loop, with its own tools, under its own name.
        assertThat(ws("tool.call")).extracting(e -> e.agent() + ":" + e.payload().get("tool"))
                .containsExactly("super:delegate", "researcher:web-search",
                        "researcher:fetch-page", "researcher:fetch-page");

        // Lifecycle: started → completed, and the worker ends idle.
        assertThat(protocol).extracting(AgentEvent::type)
                .startsWith(AgentEvent.STARTED).endsWith(AgentEvent.COMPLETED);
        assertThat(ws("agent.status")).filteredOn(e -> "researcher".equals(e.agent()))
                .last().satisfies(e -> assertThat(e.payload()).containsEntry("status", "idle"));

        // The user hears ONE answer, from the Manager, carrying the findings.
        assertThat(ws("task.done")).singleElement().satisfies(e -> {
            String text = (String) e.payload().get("text");
            assertThat(text).contains("Node.js 22 'Jod'").contains("Here's what I found");
        });
    }

    @Test
    void theResearcherNarratesItsWorkAsTheExactStageSequence() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"), researcherRating(0.9));
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        assertThat(stages()).containsExactly("searching", "found", "reading", "reading", "summarizing");
        assertThat(labels()).containsExactly(
                "Searching the web…",
                "Found 3 sources",
                "Reading nodejs.org (1/3)",
                "Reading endoflife.date (2/3)",
                "Summarizing findings…");

        // Every stage reaches the console as a plain agent.status — no new event type.
        assertThat(ws("agent.status")).filteredOn(e -> "researcher".equals(e.agent()))
                .extracting(e -> e.payload().get("task"))
                .contains("Searching the web…", "Found 3 sources", "Summarizing findings…");
    }

    @Test
    void theUiIsToldWhatTheAnswerRestsOnSoTheUserCanJudgeIt() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"), researcherRating(0.9));
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        String id = (String) ws("delegation").get(0).payload().get("id");
        assertThat(ws("delegation").get(0).payload()).containsEntry("attempt", 1);

        assertThat(ws("delegation.result")).singleElement().satisfies(e -> {
            // Paired with its delegation by the command id — not by "the most recent one".
            assertThat(e.payload()).containsEntry("id", id).containsEntry("status", "completed");
            assertThat(e.payload()).containsEntry("confidence", 0.9);
            assertThat(e.payload().get("sources")).asInstanceOf(LIST).hasSize(2);
            assertThat(e.agent()).isEqualTo("researcher");
        });
    }

    @Test
    void aStoppedDelegationReportsNoConfidenceRatherThanZero() throws Exception {
        // "Cancelled" and "certain it's nothing" are different things; the UI must not
        // render a stopped delegation as a 0% confident answer.
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ManagerAgent manager = wire(managerThatDelegates("research.search"),
                researcherRating(0.9, 0.9, reached, release));
        store.append(CONV, "user", "latest node lts?");

        Thread turn = new Thread(() -> manager.handle(CONV, "msg-1", "latest node lts?"));
        turn.start();
        assertThat(reached.await(5, SECONDS)).isTrue();
        pending.cancelConversation(CONV);
        release.countDown();
        turn.join(10_000);

        assertThat(ws("delegation.result")).singleElement().satisfies(e -> {
            assertThat(e.payload()).containsEntry("status", "cancelled");
            assertThat(e.payload()).doesNotContainKey("confidence").doesNotContainKey("sources");
        });
    }

    @Test
    void aRetryIsVisibleAsASecondAttempt() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"),
                researcherRating(0.1, 0.9, new CountDownLatch(0), new CountDownLatch(0)));
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        assertThat(ws("delegation")).extracting(e -> e.payload().get("attempt")).containsExactly(1, 2);
        // Each attempt gets its own id, so the UI can pair each with its own result.
        assertThat(ws("delegation")).extracting(e -> e.payload().get("id")).doesNotHaveDuplicates();
        // The first attempt rated itself badly (its sources were read, so no cap applied);
        // the policy retried, and the UI sees both results.
        assertThat(ws("delegation.result")).extracting(e -> e.payload().get("confidence"))
                .containsExactly(0.1, 0.9);
    }

    @Test
    void aWellSourcedResultIsPassedThroughWithItsConfidence() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"), researcherRating(0.9));
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        AgentEvent completed = protocol.stream().filter(e -> e.type().equals(AgentEvent.COMPLETED))
                .findFirst().orElseThrow();
        // Two pages actually read → the cap is 1.0, so the model's own 0.9 stands.
        assertThat(completed.result().confidence()).isEqualTo(0.9);
        assertThat(completed.result().data()).extracting("sources").asList().hasSize(2);
        assertThat((String) ws("task.done").get(0).payload().get("text")).contains("confidence 90%");
    }

    @Test
    void anOverconfidentResearcherIsCappedByWhatItActuallyRead() {
        // Claims certainty, reads nothing: evidence caps it at 0.2 → low → retried, then honest.
        OllamaClient lazy = new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> t, Consumer<String> onToken) {
                return new ChatTurn("FINDINGS: Node 22.\nCONFIDENCE: 0.99", List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
        ManagerAgent manager = wire(managerThatDelegates("research.search"), lazy);
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        assertThat(protocol).filteredOn(e -> e.type().equals(AgentEvent.COMPLETED))
                .allSatisfy(e -> assertThat(e.result().confidence()).isEqualTo(0.2));
        assertThat((String) ws("task.done").get(0).payload().get("text"))
                .contains("FAILED to establish")
                .contains("State NO version, number, date, name or fact");
    }

    @Test
    void lowConfidenceFindingsAreWithheldFromTheManagerNotJustDisclaimed() {
        // The measured failure (§9): told "(LOW confidence — do NOT present this as fact)"
        // and handed the findings anyway, llama3.1:8b read past the warning and stated a
        // fabricated Node version. It cannot parrot what it never receives.
        OllamaClient lazy = new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> t, Consumer<String> onToken) {
                return new ChatTurn("FINDINGS: Node.js 24.16 'Fabricated' is the LTS.\nCONFIDENCE: 0.99",
                        List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
        ManagerAgent manager = wire(managerThatDelegates("research.search"), lazy);
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        // managerThatDelegates echoes its tool result verbatim into the answer, so this
        // asserts the findings never entered the Manager's context at all.
        String answer = (String) ws("task.done").get(0).payload().get("text");
        assertThat(answer).doesNotContain("24.16").doesNotContain("Fabricated");
        assertThat(answer).contains("withheld from you deliberately");
    }

    @Test
    void aResearcherThatStopsAfterOneSourceIsSentBackForAsecond() {
        // Live, this is where "Node.js v16.x … v25 will be the latest LTS" came from: one
        // end-of-life table, read once, with nothing to check it against (§9).
        OllamaClient onePage = new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
                List<String> results = toolResults(messages);
                boolean toldOff = messages.stream().anyMatch(msg -> "user".equals(msg.role())
                        && msg.content() != null && msg.content().contains("read one page"));
                if (results.isEmpty()) {
                    return new ChatTurn("", List.of(new ToolCall("web-search", Map.of("query", "node lts"))));
                }
                if (results.size() == 1) {
                    return new ChatTurn("", List.of(new ToolCall("fetch-page",
                            Map.of("url", "https://nodejs.org/en/about/previous-releases"))));
                }
                if (toldOff && results.size() == 2) {
                    return new ChatTurn("", List.of(new ToolCall("fetch-page",
                            Map.of("url", "https://endoflife.date/nodejs"))));
                }
                return new ChatTurn(findings(0.9), List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
        ManagerAgent manager = wire(managerThatDelegates("research.search"), onePage);
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        assertThat(ws("tool.call")).filteredOn(e -> "fetch-page".equals(e.payload().get("tool"))).hasSize(2);
        AgentEvent completed = protocol.stream().filter(e -> e.type().equals(AgentEvent.COMPLETED))
                .findFirst().orElseThrow();
        // Two independent sources → the cap lifts to 1.0, so its 0.9 stands, unhedged.
        assertThat(completed.result().confidence()).isEqualTo(0.9);
        assertThat(completed.result().data()).extracting("sources").asList().hasSize(2);
    }

    @Test
    void aResearcherThatReportsFromSnippetsIsSentBackToActuallyReadSomething() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"), researcherThatSkipsReading());
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        // It tried to report on snippets; the loop insisted; it read two pages.
        assertThat(ws("tool.call")).extracting(e -> e.payload().get("tool"))
                .containsExactly("delegate", "web-search", "fetch-page", "fetch-page");
        assertThat(stages()).containsExactly("searching", "found", "reading", "reading", "summarizing");

        AgentEvent completed = protocol.stream().filter(e -> e.type().equals(AgentEvent.COMPLETED))
                .findFirst().orElseThrow();
        // Two real sources → no cap → its own rating stands, and no retry was needed.
        assertThat(completed.result().confidence()).isEqualTo(0.9);
        assertThat(ws("delegation")).hasSize(1);
    }

    @Test
    void aLowConfidenceResultIsRetriedOnceAgainstDifferentSources() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"),
                researcherRating(0.1, 0.9, new CountDownLatch(0), new CountDownLatch(0)));
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        // Two delegations: the user sees SOUL double-checking, which is the point.
        assertThat(ws("delegation")).hasSize(2);
        assertThat(protocol).filteredOn(e -> e.type().equals(AgentEvent.COMPLETED)).hasSize(2);
        // The second attempt's result is what reaches the model, and it's no longer hedged.
        assertThat((String) ws("task.done").get(0).payload().get("text")).contains("confidence 90%");
    }

    @Test
    void aModerateResultIsPassedThroughFlaggedForHedging() {
        ManagerAgent manager = wire(managerThatDelegates("research.search"), researcherRating(0.55));
        store.append(CONV, "user", "latest node lts?");

        manager.handle(CONV, "msg-1", "latest node lts?");

        assertThat(ws("delegation")).hasSize(1); // 0.55 is above retry-below: no retry
        assertThat((String) ws("task.done").get(0).payload().get("text"))
                .contains("moderate confidence 55%")
                .contains("could not be fully verified");
    }

    @Test
    void stoppingMidResearchWindsTheWorkerDownAndTellsTheManager() throws Exception {
        CountDownLatch researcherReached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ManagerAgent manager = wire(managerThatDelegates("research.search"),
                researcherRating(0.9, 0.9, researcherReached, release));
        store.append(CONV, "user", "latest node lts?");

        // The Manager blocks in its loop while research runs, so drive the turn off-thread.
        Thread turn = new Thread(() -> manager.handle(CONV, "msg-1", "latest node lts?"));
        turn.start();
        assertThat(researcherReached.await(5, SECONDS)).isTrue(); // research is genuinely in flight

        assertThat(pending.cancelConversation(CONV)).isTrue(); // the stop button
        release.countDown();
        turn.join(10_000);

        // The worker stopped cooperatively at its next step boundary and said so…
        assertThat(protocol).extracting(AgentEvent::type).contains(AgentEvent.CANCELLED);
        assertThat(protocol).noneMatch(e -> e.type().equals(AgentEvent.COMPLETED));
        assertThat(ws("agent.status")).filteredOn(e -> "researcher".equals(e.agent()))
                .last().satisfies(e -> assertThat(e.payload()).containsEntry("status", "idle"));
        // …and the Manager acknowledged rather than answering from memory.
        assertThat((String) ws("task.done").get(0).payload().get("text")).contains("cancelled");
    }

    @Test
    void anUnsupportedCapabilityComesBackAsACleanToolError() {
        ManagerAgent manager = wire(managerThatDelegates("email.send"), researcherRating(0.9));
        store.append(CONV, "user", "email my mum");

        manager.handle(CONV, "msg-1", "email my mum");

        assertThat(ws("delegation")).isEmpty(); // nothing was dispatched
        assertThat(protocol).isEmpty();         // no worker was disturbed
        String answer = (String) ws("task.done").get(0).payload().get("text");
        assertThat(answer).contains("no agent supports 'email.send'")
                .contains("research.search"); // told what IS available, so it can recover
    }

    @Test
    void withNoWorkersRegisteredTheManagerIsOfferedNoDelegateToolAtAll() {
        AtomicInteger offered = new AtomicInteger();
        OllamaClient inspect = new OllamaClient() {
            @Override
            public ChatTurn chat(String m, List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
                offered.set((int) tools.stream().filter(t -> t.name().equals("delegate")).count());
                onToken.accept("Hello.");
                return new ChatTurn("Hello.", List.of());
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
        // A fresh registry with nothing in it — SOUL before it had a fleet.
        InProcessCommandBus emptyBus = new InProcessCommandBus();
        DelegateTool delegate = new DelegateTool(new InProcessAgentRegistry(emptyBus),
                new PendingDelegations(emptyBus, new InProcessEventBus()), new SoulProperties(), sink);

        assertThat(delegate.forConversation(CONV)).isEmpty();

        ManagerAgent manager = wire(inspect, researcherRating(0.9));
        store.append(CONV, "user", "hi");
        manager.handle(CONV, "msg-1", "hi");
        assertThat(offered).hasValue(1); // …but WITH the researcher registered, it is offered
    }
}
