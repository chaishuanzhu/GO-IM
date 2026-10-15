import { useCallback, useEffect, useRef, useState } from 'react';

/** 秒级倒计时（验证码重发冷却）。 */
export function useCountdown() {
  const [left, setLeft] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  const stop = useCallback(() => {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
  }, []);

  const start = useCallback((seconds: number) => {
    stop();
    setLeft(seconds);
    timer.current = setInterval(() => {
      setLeft((s) => {
        if (s <= 1) {
          stop();
          return 0;
        }
        return s - 1;
      });
    }, 1000);
  }, [stop]);

  useEffect(() => stop, [stop]);

  return { left, start };
}
