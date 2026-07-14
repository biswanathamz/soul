import { describe, expect, it } from 'vitest';
import { CODE_NOTICE, SentenceStream } from './sentences';

describe('SentenceStream', () => {
  it('emits a sentence once its terminator is confirmed by the next chunk', () => {
    const s = new SentenceStream();
    expect(s.push('Hello wor')).toEqual([]);
    expect(s.push('ld.')).toEqual([]); // terminator last — could be "3." of "3.14"
    expect(s.push(' How are you? ')).toEqual(['Hello world.', 'How are you?']);
    expect(s.flush()).toEqual([]);
  });

  it('flush returns the unterminated remainder', () => {
    const s = new SentenceStream();
    expect(s.push('First one. And then some trailing words')).toEqual(['First one.']);
    expect(s.flush()).toEqual(['And then some trailing words']);
  });

  it('does not split decimals or common abbreviations', () => {
    const s = new SentenceStream();
    const out = s.push('Pi is 3.14 exactly. Dr. Smith agrees, e.g. always. Done. ');
    expect(out).toEqual(['Pi is 3.14 exactly.', 'Dr. Smith agrees, e.g. always.', 'Done.']);
  });

  it('treats newlines as boundaries (lists, paragraphs)', () => {
    const s = new SentenceStream();
    expect(s.push('- first point\n- second point\n')).toEqual(['- first point', '- second point']);
  });

  it('replaces fenced code with a single notice, even split across chunks', () => {
    const s = new SentenceStream();
    const out: string[] = [];
    out.push(...s.push('Here is the function. ``'));
    out.push(...s.push('`python\ndef x():\n    return 1\n`'));
    out.push(...s.push('``'));
    out.push(...s.push(' That should work. '));
    expect(out).toEqual(['Here is the function.', CODE_NOTICE, 'That should work.']);
  });

  it('never leaks code content on flush when the fence is still open', () => {
    const s = new SentenceStream();
    const out = s.push('Sure. ```js\nsecret();');
    expect(out).toEqual(['Sure.', CODE_NOTICE]);
    expect(s.flush()).toEqual([]);
  });
});
