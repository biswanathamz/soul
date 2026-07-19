import { activityOf, useFaceStore, type FaceActivity } from '../../state/faceStore';

function hintFor(activity: FaceActivity): string {
  switch (activity) {
    case 'listening':
      return 'Listening…';
    case 'thinking':
      return 'Thinking…';
    case 'speaking':
      return '';
    default:
      return 'Waiting — ask me anything';
  }
}

/**
 * SOUL's face — center stage (docs/voice-and-face.md §3). Two visual layers:
 * data-activity (idle/listening/thinking/speaking) and data-mood
 * (neutral/pleased/concerned); all rendering rules live in tokens.css.
 * The caption doubles as captions for what SOUL says (accessibility).
 */
export function SoulFace() {
  const listening = useFaceStore((s) => s.listening);
  const speaking = useFaceStore((s) => s.speaking);
  const thinking = useFaceStore((s) => s.thinking);
  const offline = useFaceStore((s) => s.offline);
  const mood = useFaceStore((s) => s.mood);
  const caption = useFaceStore((s) => s.caption);
  const busy = useFaceStore((s) => s.busy);

  const activity = activityOf({ listening, speaking, thinking, offline, mood, caption, busy });

  return (
    <div className="flex flex-col items-center gap-7 select-none">
      <div
        className="face"
        data-activity={activity}
        data-mood={mood}
        role="img"
        aria-label={`SOUL is ${activity}${mood !== 'neutral' ? `, ${mood}` : ''}`}
      >
        <div className="face-ring" />
        <div className="face-disc">
          <div className="face-eyes">
            <span className="face-eye" />
            <span className="face-eye" />
          </div>
          <div className="face-mouth" />
        </div>
      </div>
      <p className="face-caption" aria-live="polite">
        {offline ? 'Connection lost — trying to reach SOUL…' : caption || hintFor(activity)}
      </p>
    </div>
  );
}
