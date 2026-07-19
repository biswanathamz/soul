package com.soul.orchestrator.agent;

/**
 * Vets a final answer before the loop accepts it. Return {@code null} to accept, or a
 * nudge to hand back to the model, which then gets one more step to do better.
 *
 * <p>This is how a requirement stops being a polite request in a prompt and becomes
 * something the agent cannot skip: the first live run showed an 8B model reading the
 * Researcher persona's "a snippet is not evidence" and reporting from snippets anyway
 * (docs/researcher-agent.md §9). A gate makes the loop insist.
 *
 * <p><b>Only for agents that don't stream.</b> By the time a gate sees an answer, a
 * streaming agent has already sent it to the user token by token — and spoken it aloud.
 * Workers stream nothing, so vetoing costs them only time. The Manager streams, which is
 * why its low-confidence guarantee is enforced by withholding evidence instead (§5.1).
 */
public interface AnswerGate {

    AnswerGate ACCEPT = answer -> null;

    /** @return null to accept the answer, or the nudge to send back. */
    String vet(String answer);
}
