import { describe, expect, it } from 'vitest';
import { stripMarkdownForSpeech } from './tts';

describe('stripMarkdownForSpeech', () => {
  it('replaces fenced code blocks with an announcement', () => {
    const md = 'Here you go:\n\n```python\nprint("hi")\n```\n\nDone.';
    const out = stripMarkdownForSpeech(md);
    expect(out).toContain('Code block omitted.');
    expect(out).not.toContain('print');
  });

  it('unwraps inline code, links and images', () => {
    expect(stripMarkdownForSpeech('Use `lru_cache` — see [docs](https://x.y) and ![alt](i.png)')).toBe(
      'Use lru_cache — see docs and alt',
    );
  });

  it('strips headings, emphasis and list markers', () => {
    expect(stripMarkdownForSpeech('# Title\n\n- **bold** point\n- _quiet_ point')).toBe(
      'Title bold point quiet point',
    );
  });

  it('drops table rows and collapses whitespace', () => {
    const md = 'Before\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter';
    expect(stripMarkdownForSpeech(md)).toBe('Before After');
  });
});
