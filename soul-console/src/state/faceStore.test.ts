import { describe, expect, it } from 'vitest';
import { activityOf, initialFace, reduceFace, type FaceEvent, type FaceState } from './faceStore';

const run = (events: FaceEvent[], from: FaceState = initialFace) =>
  events.reduce(reduceFace, from);

describe('reduceFace — activity layer', () => {
  it('starts idle', () => {
    expect(activityOf(initialFace)).toBe('idle');
  });

  it('listening wins over speaking wins over thinking (priority §3.1)', () => {
    const all = run([
      { type: 'agent.status', status: 'thinking' },
      { type: 'speech.start' },
      { type: 'stt.start' },
    ]);
    expect(activityOf(all)).toBe('listening');
    const noListen = reduceFace(all, { type: 'stt.stop' });
    expect(activityOf(noListen)).toBe('speaking');
    const noSpeak = reduceFace(noListen, { type: 'speech.end' });
    expect(activityOf(noSpeak)).toBe('thinking');
    const done = reduceFace(noSpeak, { type: 'agent.status', status: 'idle' });
    expect(activityOf(done)).toBe('idle');
  });

  it('thinking follows agent.status with the task as caption', () => {
    const s = run([{ type: 'agent.status', status: 'working', task: 'running current-time' }]);
    expect(activityOf(s)).toBe('thinking');
    expect(s.caption).toBe('running current-time');
  });

  it('speaking shows the spoken sentence as caption, cleared at speech end', () => {
    const s = run([
      { type: 'speech.start' },
      { type: 'speech.sentence', text: 'The current time is 4pm.' },
    ]);
    expect(activityOf(s)).toBe('speaking');
    expect(s.caption).toBe('The current time is 4pm.');
    expect(reduceFace(s, { type: 'speech.end' }).caption).toBe('');
  });
});

describe('reduceFace — mood layer', () => {
  it('task.done → pleased; decays back to neutral', () => {
    const pleased = run([{ type: 'task.done' }]);
    expect(pleased.mood).toBe('pleased');
    expect(pleased.thinking).toBe(false);
    expect(reduceFace(pleased, { type: 'mood.decay' }).mood).toBe('neutral');
  });

  it('error → concerned, and stops thinking/speaking', () => {
    const s = run([
      { type: 'agent.status', status: 'thinking' },
      { type: 'speech.start' },
      { type: 'error' },
    ]);
    expect(s.mood).toBe('concerned');
    expect(activityOf(s)).toBe('idle');
  });

  it('concerned clears on the next activity (new turn)', () => {
    const s = run([{ type: 'error' }, { type: 'stt.start' }]);
    expect(s.mood).toBe('neutral');
    const t = run([{ type: 'error' }, { type: 'agent.status', status: 'thinking' }]);
    expect(t.mood).toBe('neutral');
  });

  it('going offline is concerning; reconnecting clears it', () => {
    const off = run([{ type: 'connection', online: false }]);
    expect(off.mood).toBe('concerned');
    expect(off.offline).toBe(true);
    const on = reduceFace(off, { type: 'connection', online: true });
    expect(on.mood).toBe('neutral');
    expect(on.offline).toBe(false);
  });

  it('decay does not touch a concerned mood', () => {
    const s = run([{ type: 'error' }, { type: 'mood.decay' }]);
    expect(s.mood).toBe('concerned');
  });
});
