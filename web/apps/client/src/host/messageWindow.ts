/**
 * 长消息列表的窗口化计算（纯函数）。
 *
 * 为什么选「滚动位置计算」而不是 IntersectionObserver：这里要的是**确定的索引区间**
 * （顶部 / 底部各要垫多高），滚动位置能直接算出可视首尾；IO 只能告诉你「某个哨兵可见了」，
 * 还得多挂两个哨兵元素、处理异步回调，反而绕。代价是行高只能估——所以用已渲染行的
 * 平均高度回填，偏差超过阈值才重算。
 */
export const WINDOW_THRESHOLD = 150;
export const WINDOW_OVERSCAN = 20;
/** 还没量到任何行高时的兜底估值（一条消息 + 间距的常见高度）。 */
export const DEFAULT_ROW_HEIGHT = 72;

export interface WindowPlan {
  /** 是否真的启用了窗口化（条目不超过阈值时为 false，全部渲染）。 */
  readonly active: boolean;
  /** 渲染 [start, end) 这一段。 */
  readonly start: number;
  readonly end: number;
  /** 顶部 / 底部等高占位的高度（px，用于保持滚动高度不跳）。 */
  readonly topPad: number;
  readonly bottomPad: number;
}

export interface WindowInput {
  readonly itemCount: number;
  readonly scrollTop: number;
  readonly viewportHeight: number;
  /** 已渲染行的平均高度；0 表示还没量到。 */
  readonly averageHeight: number;
  /** 贴底时窗口锚在末尾（聊天默认在底部）。 */
  readonly pinnedToEnd: boolean;
  readonly threshold?: number;
  readonly overscan?: number;
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(Math.max(value, min), max);
}

export function planWindow(input: WindowInput): WindowPlan {
  const threshold = input.threshold ?? WINDOW_THRESHOLD;
  const overscan = input.overscan ?? WINDOW_OVERSCAN;
  const count = Math.max(0, Math.floor(input.itemCount));
  if (count === 0) return { active: false, start: 0, end: 0, topPad: 0, bottomPad: 0 };
  if (count <= threshold) return { active: false, start: 0, end: count, topPad: 0, bottomPad: 0 };

  const rowHeight = input.averageHeight > 0 ? input.averageHeight : DEFAULT_ROW_HEIGHT;
  const viewport = input.viewportHeight > 0 ? input.viewportHeight : rowHeight * 8;
  const visible = Math.max(1, Math.ceil(viewport / rowHeight));

  let firstVisible: number;
  let lastVisible: number;
  if (input.pinnedToEnd) {
    lastVisible = count;
    firstVisible = count - visible;
  } else {
    firstVisible = Math.floor(clamp(input.scrollTop, 0, Number.MAX_SAFE_INTEGER) / rowHeight);
    lastVisible = firstVisible + visible;
  }

  const start = clamp(firstVisible - overscan, 0, count);
  const end = clamp(lastVisible + overscan, start, count);
  return {
    active: true,
    start,
    end,
    topPad: Math.round(start * rowHeight),
    bottomPad: Math.round((count - end) * rowHeight),
  };
}

/** 已渲染行的平均高度；没有可用行时返回 undefined。 */
export function measureAverage(heights: readonly number[]): number | undefined {
  let total = 0;
  let used = 0;
  for (const height of heights) {
    if (!Number.isFinite(height) || height <= 0) continue;
    total += height;
    used += 1;
  }
  return used === 0 ? undefined : total / used;
}

/** 行高估计要不要更新（默认 15% 死区，避免量一次抖一次）。 */
export function shouldUpdateAverage(previous: number, next: number, tolerance = 0.15): boolean {
  if (!Number.isFinite(next) || next <= 0) return false;
  if (previous <= 0) return true;
  return Math.abs(next - previous) / previous > tolerance;
}
