/**
 * Self-echo rejection for barge-in (docs/voice-and-face.md §4.3).
 *
 * With barge-in on, the mic stays open while SOUL speaks. The mic constraint
 * `echoCancellation: true` does NOT remove her voice: browser AEC only reliably
 * cancels WebRTC remote streams, and SOUL plays TTS through a plain <audio>
 * element — to the canceller that's just room noise (worst on Linux). So the
 * wake loop transcribes fragments of her own speech, and the deliberately fuzzy
 * wake regex eventually false-matches one, cutting her off mid-sentence.
 *
 * The fix uses what the acoustic canceller can't: we KNOW the text she is
 * saying — the speaker pipeline announces every sentence as it starts playing.
 * If a transcript arriving from the wake loop is mostly words she just spoke,
 * it is her own voice coming back through the mic, and it is dropped before
 * wake matching. Text-level echo cancellation.
 */

/** How long a spoken sentence stays in the echo buffer. Playback of a sentence
 * plus the mic's utterance window (4 s) plus whisper's round-trip can lag the
 * moment it was announced by many seconds; 15 s covers the worst case seen. */
const WINDOW_MS = 15_000;

/** ≥ this fraction of a transcript's words matching recent speech ⇒ echo. */
const ECHO_RATIO = 0.6;

interface SpokenEntry {
  words: Set<string>;
  at: number;
}

let recent: SpokenEntry[] = [];

function tokenize(text: string): string[] {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9' ]+/gi, ' ')
    .split(/\s+/)
    .filter(Boolean);
}

function prune(now: number): void {
  recent = recent.filter((e) => now - e.at < WINDOW_MS);
}

/** Speaker pipeline calls this as each sentence starts playing. */
export function noteSpokenSentence(text: string, now = Date.now()): void {
  const words = new Set(tokenize(text));
  if (words.size === 0) return;
  prune(now);
  recent.push({ words, at: now });
}

/**
 * Is this transcript an echo of something SOUL just said?
 *
 * Word-overlap against the union of recently spoken sentences. Whisper won't
 * hear her verbatim through speaker → air → mic, so a fuzzy ratio is used for
 * transcripts of 3+ words. One- and two-word transcripts ("stop", "hey soul")
 * are the dangerous ones to over-filter — a genuine wake must never be eaten —
 * so those are echo only if EVERY word was just spoken.
 */
export function isSelfEcho(transcript: string, now = Date.now()): boolean {
  prune(now);
  if (recent.length === 0) return false;
  const words = tokenize(transcript);
  if (words.length === 0) return false;

  const spoken = new Set<string>();
  for (const e of recent) for (const w of e.words) spoken.add(w);

  const hits = words.filter((w) => spoken.has(w)).length;
  if (words.length <= 2) return hits === words.length;
  return hits / words.length >= ECHO_RATIO;
}

/** Test hook. */
export function _resetSelfEcho(): void {
  recent = [];
}
