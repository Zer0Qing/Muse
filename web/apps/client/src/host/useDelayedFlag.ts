import { useEffect, useState } from "react";

/** 骨架屏的延迟：加载快的时候不要闪一下骨架，超过这个时长才认为「真的在等」。 */
export const SKELETON_DELAY_MS = 300;

/** 纯判定：等够时长才该显示骨架。 */
export function isSkeletonDue(loading: boolean, waitedMs: number, delayMs: number = SKELETON_DELAY_MS): boolean {
  return loading && waitedMs >= delayMs;
}

/**
 * 「加载超过 delay 之后才显示骨架」的开关。
 *
 * 数据通常在几百毫秒内就到，直接上骨架会闪一下反而更晃眼；这里只负责延迟可见性，
 * 数据到了就把定时器清掉。
 */
export function useDelayedFlag(active: boolean, delayMs: number = SKELETON_DELAY_MS): boolean {
  const [due, setDue] = useState(false);

  useEffect(() => {
    if (!active) {
      setDue(false);
      return;
    }
    const timer = window.setTimeout(() => setDue(true), delayMs);
    return () => window.clearTimeout(timer);
  }, [active, delayMs]);

  return due;
}
