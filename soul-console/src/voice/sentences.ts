/**
 * Streaming sentence splitter (docs/voice-and-face.md §4.2).
 *
 * Fed token-by-token; emits complete sentences as soon as they close, so TTS can
 * start speaking seconds into a long generation. Fenced code blocks are never
 * spoken — a short notice is emitted instead and the content is skipped.
 */

export const CODE_NOTICE = "I've put the code in the chat.";

/** Trailing abbreviations whose period is not a sentence boundary. */
const ABBREV =
  /(?:\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc|e\.g|i\.e|no|inc|ltd|fig|approx)\.)$/i;

const FENCE = '```';

export class SentenceStream {
  private buf = '';
  private inFence = false;

  /** Add a streamed chunk; returns any sentences completed by it. */
  push(chunk: string): string[] {
    this.buf += chunk;
    return this.drain(false);
  }

  /** End of stream — returns whatever remains as final sentence(s). */
  flush(): string[] {
    const out = this.drain(true);
    const rest = this.inFence ? '' : this.buf.trim();
    this.buf = '';
    this.inFence = false;
    if (rest) out.push(rest);
    return out;
  }

  reset(): void {
    this.buf = '';
    this.inFence = false;
  }

  private drain(final: boolean): string[] {
    const out: string[] = [];
    // Iterate because one chunk can close a sentence, open a fence, and more.
    for (;;) {
      if (this.inFence) {
        const close = this.buf.indexOf(FENCE);
        if (close === -1) return out; // fence still open — hold everything
        this.buf = this.buf.slice(close + FENCE.length);
        this.inFence = false;
        continue;
      }

      const fence = this.buf.indexOf(FENCE);
      const region = fence === -1 ? this.buf : this.buf.slice(0, fence);
      const { sentences, consumed } = scanSentences(region, final && fence === -1);
      out.push(...sentences);

      if (fence === -1) {
        this.buf = this.buf.slice(consumed);
        return out;
      }
      // Text right before the fence is a complete thought — speak it, then the notice.
      const tail = region.slice(consumed).trim();
      if (tail) out.push(tail);
      out.push(CODE_NOTICE);
      this.buf = this.buf.slice(fence + FENCE.length);
      this.inFence = true;
    }
  }
}

function scanSentences(region: string, _final: boolean): { sentences: string[]; consumed: number } {
  const sentences: string[] = [];
  let start = 0;
  for (let i = 0; i < region.length; i++) {
    const ch = region[i];
    let boundary = false;

    if (ch === '\n') {
      boundary = true; // newlines (paragraphs, list items) always break
    } else if (ch === '.' || ch === '!' || ch === '?') {
      const next = region[i + 1];
      // Need the following char to prove the sentence closed (streaming lookahead);
      // "3.14" (digit follows) and "e.g." (abbreviation) are not boundaries.
      if (next !== undefined && /\s/.test(next) && !ABBREV.test(region.slice(Math.max(0, i - 9), i + 1))) {
        boundary = true;
      }
    }

    if (boundary) {
      const sentence = region.slice(start, i + 1).trim();
      if (sentence) sentences.push(sentence);
      start = i + 1;
    }
  }
  return { sentences, consumed: start };
}
