package com.soul.orchestrator.agent;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A deterministic backstop against over-delegation (docs/bug/latency-and-tts-interruption.md,
 * round 2). The prose in {@link DelegateTool}'s description tells the Manager to answer
 * well-known facts from memory — but a 3B model on the reference box does not reliably obey
 * it, and every misroute costs the user ~90 s and often a wrong or withheld answer. So a
 * small amount of the guarantee moves from the prompt into code: the same move the design
 * already made for the confidence policy and the answer gate.
 *
 * <p><b>Deliberately high-precision, not high-recall.</b> This only vetoes question shapes
 * that are essentially <i>always</i> answerable from training data — heads of state, capital
 * cities, historical dates, who-made-what, plain definitions — and only when the task carries
 * <i>no</i> sign of genuine recency. Everything else is left to the model's (now sharper)
 * judgement. A false veto answers a genuinely-current question from stale memory, so the bar
 * to veto is set high and any recency marker lifts it entirely.
 */
@Component
public class DelegationGuard {

    /**
     * Words that mean "this genuinely moves fast" — any of them present, and the guard steps
     * aside and lets the model decide. Note {@code current}/{@code currently} are absent on
     * purpose: they are exactly the words a user attaches to a timeless fact ("the current
     * PM of India"), so they must not by themselves force a research loop.
     */
    private static final Pattern RECENCY = Pattern.compile(
            "(?i)\\b(today|tonight|tomorrow|yesterday|now|right now|at the moment|this (week|month|morning|"
            + "evening|afternoon|season)|so far|latest|newest|recent|recently|breaking|live|as of|"
            + "up[- ]?to[- ]?date|price|prices|priced|cost|costs|worth|stock|shares|rate|rates|weather|"
            + "temperature|forecast|score|scores|standings|news|headline|headlines|release|released|"
            + "version|update|updated|schedule|202[5-9]|203\\d)\\b");

    /** Heads of state / government and monarchs — "who is the PM of France". */
    private static final Pattern LEADER_OF = Pattern.compile(
            "(?i)\\b(president|prime[\\s-]*minister|\\bpm\\b|premier|chancellor|monarch|king|queen|"
            + "emperor|head of (state|government))\\b.*\\bof\\b");

    /** "the capital (city) of X". */
    private static final Pattern CAPITAL_OF = Pattern.compile("(?i)\\bcapital\\s+(city\\s+)?of\\b");

    /** Historical dates — "when was X born / founded / written", "when did WW2 end". */
    private static final Pattern HISTORICAL_WHEN = Pattern.compile(
            "(?i)\\bwhen\\s+(was|were|did)\\b.*\\b(born|die|died|found|founded|established|created|"
            + "written|built|invented|discovered|signed|independence|end|ended|begin|began|start|"
            + "started|happen|happened|occur|occurred)\\b");

    /** Authorship / origin — "who invented X", "who wrote Y". */
    private static final Pattern WHO_MADE = Pattern.compile(
            "(?i)\\bwho\\s+(founded|invented|wrote|created|discovered|painted|composed|directed|"
            + "designed|built|developed|established)\\b");

    /** Plain definitions — "what does X mean", "define X", "what is the meaning of X". */
    private static final Pattern DEFINITION = Pattern.compile(
            "(?i)(\\bdefine\\b|\\bwhat\\s+does\\b.*\\bmean\\b|\\bmeaning\\s+of\\b|\\bwhat\\s+is\\s+the\\s+"
            + "(meaning|definition)\\b)");

    private static final Pattern[] TIMELESS = {
        LEADER_OF, CAPITAL_OF, HISTORICAL_WHEN, WHO_MADE, DEFINITION
    };

    /**
     * True when the task is a well-known fact the Manager should answer itself rather than
     * delegate. False whenever anything about the task smells current — the model keeps the
     * call in every case this is unsure about.
     */
    public boolean answerableFromMemory(String task) {
        if (task == null || task.isBlank()) {
            return false;
        }
        if (RECENCY.matcher(task).find()) {
            return false; // a real recency signal — let it delegate
        }
        for (Pattern timeless : TIMELESS) {
            if (timeless.matcher(task).find()) {
                return true;
            }
        }
        return false;
    }
}
