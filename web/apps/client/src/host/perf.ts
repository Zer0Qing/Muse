/**
 * `?perf=1` 调试开关：只在带这个参数时往控制台打快照渲染信息，平时一行都不打。
 * 不引入任何监控依赖，也不上报。
 */
export function perfFlag(search: string): boolean {
  const query = search.startsWith("?") ? search.slice(1) : search;
  try {
    return new URLSearchParams(query).get("perf") === "1";
  } catch {
    return false;
  }
}

export function formatPerfLine(messages: number, elapsedMs: number): string {
  const ms = Number.isFinite(elapsedMs) ? elapsedMs.toFixed(1) : "?";
  return `[muse perf] 快照渲染 ${messages} 条消息 / ${ms}ms`;
}
