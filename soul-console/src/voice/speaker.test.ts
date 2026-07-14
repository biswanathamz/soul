import { describe, expect, it, vi } from 'vitest';
import { createSpeaker } from './speaker';

/** Tag fake blobs with their text so we can assert playback order. */
const blobFor = (text: string) => ({ text }) as unknown as Blob;
const textOf = (b: Blob) => (b as unknown as { text: string }).text;

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('createSpeaker', () => {
  it('plays sentences strictly in order even if a later synth resolves first', async () => {
    const d1 = deferred<Blob>();
    const d2 = deferred<Blob>();
    const synths = [d1.promise, d2.promise];
    const played: string[] = [];
    const onStart = vi.fn();
    const onEnd = vi.fn();

    const sp = createSpeaker({
      synth: () => synths.shift()!,
      play: async (b) => {
        played.push(textOf(b));
      },
      onStart,
      onEnd,
    });
    sp.enqueue('one');
    sp.enqueue('two');

    d2.resolve(blobFor('two')); // second finishes synth FIRST
    await flush();
    expect(played).toEqual([]); // still waiting on sentence one

    d1.resolve(blobFor('one'));
    await flush();
    expect(played).toEqual(['one', 'two']);
    expect(onStart).toHaveBeenCalledTimes(1);

    sp.finish();
    await flush();
    expect(onEnd).toHaveBeenCalledTimes(1);
    expect(onEnd).toHaveBeenCalledWith(true);
  });

  it('reports neural-down when the FIRST synth fails before anything played', async () => {
    const onNeuralDown = vi.fn();
    const play = vi.fn(async () => {});
    const sp = createSpeaker({
      synth: () => Promise.reject(new Error('down')),
      play,
      onNeuralDown,
    });
    sp.enqueue('hello there');
    await flush();
    expect(onNeuralDown).toHaveBeenCalledTimes(1);
    expect(play).not.toHaveBeenCalled();
  });

  it('skips a mid-stream failure and keeps speaking', async () => {
    let call = 0;
    const played: string[] = [];
    const onEnd = vi.fn();
    const onNeuralDown = vi.fn();
    const sp = createSpeaker({
      synth: (t) => (++call === 2 ? Promise.reject(new Error('blip')) : Promise.resolve(blobFor(t))),
      play: async (b) => {
        played.push(textOf(b));
      },
      onEnd,
      onNeuralDown,
    });
    sp.enqueue('one');
    await flush();
    sp.enqueue('two');
    sp.enqueue('three');
    sp.finish();
    await flush();
    expect(played).toEqual(['one', 'three']);
    expect(onNeuralDown).not.toHaveBeenCalled();
    expect(onEnd).toHaveBeenCalledTimes(1);
    expect(onEnd).toHaveBeenCalledWith(true);
  });

  it('cancel stops playback and drops the queue', async () => {
    const d1 = deferred<Blob>();
    const played: string[] = [];
    const stop = vi.fn();
    const sp = createSpeaker({
      synth: () => d1.promise,
      play: async (b) => {
        played.push(textOf(b));
      },
      stop,
    });
    sp.enqueue('one');
    sp.cancel();
    d1.resolve(blobFor('one'));
    await flush();
    expect(played).toEqual([]);
    expect(stop).toHaveBeenCalled();
  });
});
