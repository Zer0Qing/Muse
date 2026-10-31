import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from "react";
import { measureAverage, planWindow, shouldUpdateAverage, type WindowPlan } from "./messageWindow.js";

interface UseMessageWindowOptions {
  readonly scrollRef: RefObject<HTMLDivElement | null>;
  readonly itemCount: number;
  /** 贴底时窗口锚在末尾（聊天默认在底部）。 */
  readonly pinnedToEnd: boolean;
  /** 会话变化等需要重新量的时机。 */
  readonly resetKey: string;
}

/**
 * 长列表窗口化：按滚动位置算出要渲染的区间，两端用等高占位撑住总高度。
 *
 * 行高只能估：渲染后量一次已渲染行的平均高度，偏差超过 15% 才更新估计（带死区，
 * 避免量一次抖一次）。条目不超过阈值时 plan.active 为 false，调用方照常全量渲染。
 */
export function useMessageWindow({ scrollRef, itemCount, pinnedToEnd, resetKey }: UseMessageWindowOptions): WindowPlan {
  const [metrics, setMetrics] = useState({ top: 0, height: 0 });
  const averageRef = useRef(0);
  const [averageTick, setAverageTick] = useState(0);

  // 滚动：rAF 节流，只记位置与视口高度；容器尺寸变化也要重算。
  useEffect(() => {
    const element = scrollRef.current;
    if (element === null) return;
    let frame = 0;
    const read = () => {
      frame = 0;
      setMetrics({ top: element.scrollTop, height: element.clientHeight });
    };
    const onScroll = () => {
      if (frame !== 0) return;
      frame = window.requestAnimationFrame(read);
    };
    element.addEventListener("scroll", onScroll, { passive: true });
    read();
    const observer = typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(read);
    observer?.observe(element);
    return () => {
      element.removeEventListener("scroll", onScroll);
      if (frame !== 0) window.cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [scrollRef, resetKey]);

  const plan = useMemo(
    () =>
      planWindow({
        itemCount,
        scrollTop: metrics.top,
        viewportHeight: metrics.height,
        averageHeight: averageRef.current,
        pinnedToEnd,
      }),
    // averageTick 是「估算值刚变过」的信号，ref 本身不会触发重算。
    [itemCount, metrics, pinnedToEnd, averageTick],
  );

  // 渲染后量一次行高；偏差够大才更新估计并重算窗口。
  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (element === null) return;
    const rows = Array.from(element.querySelectorAll<HTMLElement>("[data-host-row]"));
    const average = measureAverage(rows.map((row) => row.offsetHeight));
    if (average === undefined || !shouldUpdateAverage(averageRef.current, average)) return;
    averageRef.current = average;
    setAverageTick((tick) => tick + 1);
  }, [plan.start, plan.end, itemCount, resetKey, scrollRef]);

  return plan;
}
