import { beforeEach, describe, expect, it } from 'vitest';
import { _resetSelfEcho, isSelfEcho, noteSpokenSentence } from './selfEcho';

/**
 * Both directions matter, but the second is the dangerous one: dropping SOUL's
 * own voice is the feature; eating a genuine "Hey SOUL" would break barge-in
 * outright — the exact thing this filter exists to make usable.
 */
describe('selfEcho', () => {
  const T = 1_000_000; // fixed clock

  beforeEach(() => _resetSelfEcho());

  it('drops a verbatim echo of a sentence SOUL just spoke', () => {
    noteSpokenSentence('The current Prime Minister of India is Narendra Modi.', T);
    expect(isSelfEcho('the current prime minister of india is narendra modi', T + 3000)).toBe(true);
  });

  it('drops a degraded echo — whisper mishears some words through the speaker', () => {
    noteSpokenSentence('I could not find a reliable source for the current Bitcoin price.', T);
    // 8 of 11 words survive the speaker → air → mic → whisper round-trip.
    expect(isSelfEcho('I good not find a reliable horse for the Bitcoin price', T + 4000)).toBe(true);
  });

  it('drops a fragment matched across two sentences', () => {
    noteSpokenSentence('Of course.', T);
    noteSpokenSentence('World War 2 ended in 1945.', T + 500);
    expect(isSelfEcho('of course world war 2 ended', T + 3000)).toBe(true);
  });

  it('lets a genuine wake through even while SOUL is speaking', () => {
    noteSpokenSentence('The capital of Japan is Tokyo, a fascinating city.', T);
    expect(isSelfEcho('hey soul what time is it', T + 2000)).toBe(false);
  });

  it('never eats a bare "hey soul" unless BOTH words were just spoken', () => {
    noteSpokenSentence('Music soothes the soul, as they say.', T); // she said "soul"…
    expect(isSelfEcho('hey soul', T + 2000)).toBe(false); // …but not "hey" → genuine wake
  });

  it('never eats a bare "stop" unless SOUL just said that word', () => {
    noteSpokenSentence('The research is still running.', T);
    expect(isSelfEcho('stop', T + 2000)).toBe(false);
  });

  it('forgets sentences after the window expires', () => {
    noteSpokenSentence('The current Prime Minister of India is Narendra Modi.', T);
    expect(isSelfEcho('the current prime minister of india is narendra modi', T + 20_000)).toBe(false);
  });

  it('is inert when nothing has been spoken', () => {
    expect(isSelfEcho('hey soul', T)).toBe(false);
    expect(isSelfEcho('', T)).toBe(false);
  });
});
