package com.soul.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The backstop's whole value is precision: it must veto the over-delegations that made
 * "who is the PM of India" a 90-second wrong answer, and it must NOT veto a genuinely
 * current question, which would answer it from stale memory (docs/bug/…). Both directions
 * are pinned here because the second is the dangerous one.
 */
class DelegationGuardTest {

    private final DelegationGuard guard = new DelegationGuard();

    @ParameterizedTest
    @ValueSource(strings = {
        "Who is the Prime Minister of India?",
        "who is the current PM of India",         // "current" must NOT rescue a delegation
        "Tell me about the president of France",
        "What is the capital of Australia?",
        "the capital city of Brazil",
        "When was Mahatma Gandhi born?",
        "when did World War 2 end",
        "Who invented the telephone?",
        "who wrote Pride and Prejudice",
        "What does photosynthesis mean?",
        "define entropy",
        "what is the meaning of serendipity",
    })
    void vetoesWellKnownFacts(String task) {
        assertThat(guard.answerableFromMemory(task))
                .as("should answer from memory: %s", task)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "latest Node.js LTS version",             // the canonical genuine research task
        "what is the price of Bitcoin",
        "current weather in London",
        "who won the cricket match today",
        "what is the score right now",
        "the latest iPhone",
        "what is the current stock price of Apple",
        "any breaking news about the election",
        "who is the president now that they resigned yesterday",
        "what happened this week in tech",
        "release date of the new GTA",
    })
    void stepsAsideForGenuinelyCurrentQuestions(String task) {
        assertThat(guard.answerableFromMemory(task))
                .as("must not block, this is time-sensitive: %s", task)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "write me a haiku about the sea",
        "what is 2 + 2",
        "translate hello into Spanish",
        "summarize this paragraph for me",
    })
    void leavesNonFactQuestionsToTheModel(String task) {
        // Not a timeless-fact shape, so the guard simply does not intervene — the model
        // answers these directly anyway; the guard only ever removes a bad delegation.
        assertThat(guard.answerableFromMemory(task)).isFalse();
    }

    @Test
    void blankTaskIsNeverBlocked() {
        assertThat(guard.answerableFromMemory("")).isFalse();
        assertThat(guard.answerableFromMemory(null)).isFalse();
        assertThat(guard.answerableFromMemory("   ")).isFalse();
    }
}
