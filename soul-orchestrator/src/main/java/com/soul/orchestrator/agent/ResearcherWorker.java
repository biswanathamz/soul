package com.soul.orchestrator.agent;

import com.soul.orchestrator.config.SoulProperties;
import com.soul.orchestrator.ollama.ChatMessage;
import com.soul.orchestrator.ollama.ToolCall;
import com.soul.orchestrator.protocol.AgentCommand;
import com.soul.orchestrator.protocol.AgentDescriptor;
import com.soul.orchestrator.protocol.AgentEvent;
import com.soul.orchestrator.protocol.AgentRegistry;
import com.soul.orchestrator.protocol.CancellationRegistry;
import com.soul.orchestrator.protocol.EventBus;
import com.soul.orchestrator.protocol.TaskResult;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Researcher — SOUL's first worker agent (docs/researcher-agent.md §4).
 *
 * <p>It is {@link AgentLoop} plus command/event plumbing: consume a {@code task}
 * command, run the loop with the research skills, narrate staged progress, and publish
 * the outcome as a {@code task.*} event. It has no conversation store and never speaks to
 * the user — its "answer" is the payload of {@code task.completed}.
 */
@Component
public class ResearcherWorker {

    private static final Logger log = LoggerFactory.getLogger(ResearcherWorker.class);
    private static final String AGENT = "researcher";
    private static final String WEB_SEARCH = "web-search";
    private static final String FETCH_PAGE = "fetch-page";

    /** The model's self-rating, per the researcher persona's required final format. */
    private static final Pattern CONFIDENCE_LINE =
            Pattern.compile("(?im)^\\s*CONFIDENCE:\\s*(\\d?\\.?\\d+)\\s*$");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");
    private static final Pattern TITLE_LINE = Pattern.compile("(?im)^Title:\\s*(.+)$");
    /** A rating we can't read is a format failure, not evidence of certainty. */
    private static final double UNRATED = 0.5;

    private final SoulProperties props;
    private final AgentLoop loop;
    private final AgentRegistry registry;
    private final EventBus events;
    private final CancellationRegistry cancellation;

    public ResearcherWorker(SoulProperties props, AgentLoop loop, AgentRegistry registry,
            EventBus events, CancellationRegistry cancellation) {
        this.props = props;
        this.loop = loop;
        this.registry = registry;
        this.events = events;
        this.cancellation = cancellation;
    }

    /**
     * Join the fleet by declaring what this agent offers. Config IS the registry's
     * content — no researcher entry means no researcher, and the delegate tool simply
     * has nothing to offer (§3.4).
     */
    @PostConstruct
    void register() {
        SoulProperties.Agent cfg = props.getAgents().get(AGENT);
        if (cfg == null || cfg.getCapabilities().isEmpty()) {
            log.info("no '{}' agent configured with capabilities — not registering", AGENT);
            return;
        }
        registry.register(
                new AgentDescriptor(AGENT, cfg.getDescription(), Set.copyOf(cfg.getCapabilities())),
                this::onCommand);
    }

    /** The worker's inbox. Task commands do the work; cancels just raise the flag. */
    void onCommand(AgentCommand command) {
        if (command.isCancel()) {
            // Cheap and non-blocking by design: this runs on the caller's thread precisely
            // so it cannot queue behind the task it is stopping.
            cancellation.cancel(command.cancelTarget());
            return;
        }
        if (command.isTask()) {
            research(command);
        }
    }

    private void research(AgentCommand command) {
        cancellation.begin(command.id());
        try {
            events.publish(AgentEvent.started(command, AGENT));
            Narrator narrator = new Narrator(command);

            LoopOutcome outcome = loop.run(LoopSpec.forAgent(AGENT)
                    .conversation(command.conversationId())
                    .text(taskOf(command))
                    .history(List.of(ChatMessage.user(prompt(command))))
                    .cancelledWhen(() -> cancellation.isCancelled(command.id()))
                    .observedBy(narrator)
                    .answerGate(narrator::mustReadEnough)
                    .build());

            switch (outcome.status()) {
                case ANSWERED -> events.publish(
                        AgentEvent.completed(command, AGENT, result(outcome.text(), narrator.sources())));
                case CANCELLED -> events.publish(AgentEvent.cancelled(command, AGENT));
                case FAILED -> events.publish(AgentEvent.failed(command, AGENT, outcome.text()));
                case EXHAUSTED -> events.publish(AgentEvent.failed(command, AGENT,
                        "ran out of steps before reaching a conclusion"));
            }
        } catch (RuntimeException e) {
            log.error("researcher failed on command {}", command.id(), e);
            events.publish(AgentEvent.failed(command, AGENT, String.valueOf(e.getMessage())));
        } finally {
            cancellation.end(command.id());
        }
    }

    /**
     * Confidence = the model's self-rating, clamped by what it actually read (§4.3). The
     * source count is counted here from real successful fetches — never taken from the
     * model's word for it.
     */
    private TaskResult result(String text, List<Map<String, Object>> sources) {
        String answer = text == null ? "" : text;
        double selfRating = selfRating(answer);
        String summary = CONFIDENCE_LINE.matcher(answer).replaceAll("").strip();
        return TaskResult.withEvidenceCap(selfRating, sources.size(), summary, Map.of("sources", sources));
    }

    private double selfRating(String text) {
        Matcher m = CONFIDENCE_LINE.matcher(text);
        if (!m.find()) {
            log.warn("researcher gave no CONFIDENCE line — treating as {}", UNRATED);
            return UNRATED;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return UNRATED;
        }
    }

    private String taskOf(AgentCommand command) {
        Object task = command.payload().get("task");
        return task == null ? "" : task.toString();
    }

    /** The task, plus the retry's instruction to go find genuinely different sources (§5.1). */
    private String prompt(AgentCommand command) {
        StringBuilder sb = new StringBuilder("Research task: ").append(taskOf(command));
        List<String> exclude = excluded(command);
        if (!exclude.isEmpty()) {
            sb.append("\n\nA previous attempt used these domains and produced a poorly-supported "
                    + "result: ").append(String.join(", ", exclude))
                    .append(". Search again, passing exclude_domains to web-search, and find "
                            + "genuinely different sources.");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> excluded(AgentCommand command) {
        Object value = command.payload().get("excludeDomains");
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * Turns loop activity into the staged progress the UI ticks through (§3.2c). The loop
     * reports what it did; naming the stages is the worker's job, because "searching" and
     * "reading" are research vocabulary, not loop vocabulary.
     */
    private final class Narrator implements LoopObserver {

        private final AgentCommand command;
        private final List<Map<String, Object>> sources = new ArrayList<>();
        private int found;
        private int read;
        private boolean summarizing;

        private Narrator(AgentCommand command) {
            this.command = command;
        }

        List<Map<String, Object>> sources() {
            return List.copyOf(sources);
        }

        /**
         * Goal 5 — "several independent sources, not one" — enforced rather than requested.
         *
         * <p>Live runs showed both ways an 8B model dodges it: reporting straight from the
         * snippets (0 sources), and stopping after one page. One page is where SOUL got
         * "Node.js v16.x… v25 will be the latest LTS" out of a single end-of-life table
         * with nothing to contradict it. The loop insists once; if the model still won't,
         * the evidence cap prices the result honestly (1 source ⇒ ≤0.6 ⇒ hedged).
         */
        String mustReadEnough(String answer) {
            int wanted = Math.min(props.getResearch().getMinSources(), found);
            if (found == 0 || sources.size() >= wanted) {
                return null; // nothing to read, or it read enough — let it report
            }
            if (sources.isEmpty()) {
                return "You have not opened a single one of those results. A snippet is not "
                        + "evidence — it is a headline. Call fetch-page on the most promising "
                        + "results (different sites, not the same one twice), read what they "
                        + "actually say, and only then report your findings.";
            }
            return "You have read one page. One page cannot be checked against anything — if it "
                    + "is out of date or you misread it, nothing catches that. Call fetch-page on "
                    + "another result from a DIFFERENT site, see whether the two agree, and say so "
                    + "in your findings.";
        }

        @Override
        public void onStep(int step) {
            // The model calling home after reading something is it composing its findings.
            if (read > 0 && !summarizing) {
                summarizing = true;
                events.publish(AgentEvent.progress(command, AGENT, "summarizing", "Summarizing findings…"));
            }
        }

        @Override
        public void onToolCall(ToolCall call) {
            if (WEB_SEARCH.equals(call.name())) {
                events.publish(AgentEvent.progress(command, AGENT, "searching", "Searching the web…"));
            } else if (FETCH_PAGE.equals(call.name())) {
                read++;
                // A URL we can't parse a host from still gets a readable line, not
                // "Reading  (1/5)" with a hole in it (seen live).
                String host = hostOf(String.valueOf(call.arguments().get("url")));
                String what = host.isBlank() ? "a source" : host;
                String label = found > 0
                        ? "Reading " + what + " (" + read + "/" + found + ")"
                        : "Reading " + what;
                events.publish(AgentEvent.progress(command, AGENT, "reading", label,
                        read, found > 0 ? found : null));
            }
        }

        @Override
        public void onToolResult(ToolCall call, String output) {
            if (WEB_SEARCH.equals(call.name())) {
                found = distinctUrls(output);
                events.publish(AgentEvent.progress(command, AGENT, "found",
                        "Found " + found + " source" + (found == 1 ? "" : "s"), null, found));
            } else if (FETCH_PAGE.equals(call.name()) && isEvidence(output)) {
                // Only a page we actually read counts — this is what caps confidence.
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("title", titleOf(output));
                source.put("url", String.valueOf(call.arguments().get("url")));
                sources.add(source);
            }
        }
    }

    private static boolean isEvidence(String output) {
        return output != null && !output.startsWith("Error:") && !output.startsWith("Refused:");
    }

    private static int distinctUrls(String output) {
        Set<String> hosts = new LinkedHashSet<>();
        Matcher m = URL_IN_TEXT.matcher(output == null ? "" : output);
        while (m.find()) {
            hosts.add(hostOf(m.group()));
        }
        hosts.remove("");
        return hosts.size();
    }

    private static String titleOf(String output) {
        Matcher m = TITLE_LINE.matcher(output);
        return m.find() ? m.group(1).strip() : "source";
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
