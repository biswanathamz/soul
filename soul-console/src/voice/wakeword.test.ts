import { describe, expect, it } from 'vitest';
import { isStopPhrase, matchWake } from './wakeword';

describe('matchWake', () => {
  it('matches "hey soul" and "hi soul" with nothing after', () => {
    expect(matchWake('hey soul')).toEqual({ matched: true, remainder: '' });
    expect(matchWake('Hi Soul')).toEqual({ matched: true, remainder: '' });
    expect(matchWake('Hey, Soul!')).toEqual({ matched: true, remainder: '' });
  });

  it('returns the command spoken in the same breath', () => {
    expect(matchWake('hey soul what time is it')).toEqual({
      matched: true,
      remainder: 'what time is it',
    });
    expect(matchWake('Hi Soul, open the chat please.')).toEqual({
      matched: true,
      remainder: 'open the chat please.',
    });
  });

  it('ignores leading chatter before the wake phrase', () => {
    expect(matchWake('um okay hey soul turn it up')).toEqual({
      matched: true,
      remainder: 'turn it up',
    });
  });

  it('tolerates common recognizer mishears of "soul"', () => {
    expect(matchWake('hey seoul what is the weather').matched).toBe(true);
    expect(matchWake('hi sole').matched).toBe(true);
    expect(matchWake('Hey Saul, what time is it?').matched).toBe(true); // whisper loves names
    expect(matchWake('hey sol').matched).toBe(true);
  });

  it('does not fire without the greeting or on lookalike words', () => {
    expect(matchWake('soul').matched).toBe(false); // bare name — too false-positive-prone
    expect(matchWake('my soul is tired').matched).toBe(false);
    expect(matchWake('hey solomon').matched).toBe(false);
    expect(matchWake('please console yourself').matched).toBe(false);
    expect(matchWake('hey soulmate').matched).toBe(false);
    expect(matchWake('the whole soul thing').matched).toBe(false);
  });
});

describe('isStopPhrase', () => {
  it('hears the ways people actually call SOUL off', () => {
    expect(isStopPhrase('stop')).toBe(true);
    expect(isStopPhrase('Stop.')).toBe(true);
    expect(isStopPhrase('stop it')).toBe(true);
    expect(isStopPhrase('cancel')).toBe(true);
    expect(isStopPhrase('never mind')).toBe(true);
    expect(isStopPhrase('  abort ')).toBe(true);
  });

  it('needs the whole utterance — "stop" inside a sentence is just a word', () => {
    // The mic is open mid-research; cancelling on any overheard "stop" would be awful.
    expect(isStopPhrase("don't stop")).toBe(false);
    expect(isStopPhrase('stop the count')).toBe(false);
    expect(isStopPhrase('when does the bus stop running')).toBe(false);
    expect(isStopPhrase('what time does the shop stop')).toBe(false);
    expect(isStopPhrase('cancel my subscription')).toBe(false);
  });
});
