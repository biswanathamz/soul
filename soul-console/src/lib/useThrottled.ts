import { useEffect, useRef, useState } from 'react';

/** Trailing-edge throttle — used to cap markdown re-parses while streaming (TDD §4.2). */
export function useThrottled<T>(value: T, ms: number): T {
  const [throttled, setThrottled] = useState(value);
  const lastRun = useRef(0);

  useEffect(() => {
    const remaining = ms - (Date.now() - lastRun.current);
    if (remaining <= 0) {
      lastRun.current = Date.now();
      setThrottled(value);
      return;
    }
    const timer = window.setTimeout(() => {
      lastRun.current = Date.now();
      setThrottled(value);
    }, remaining);
    return () => clearTimeout(timer);
  }, [value, ms]);

  return throttled;
}
