import { DEFAULT_LOCALE, type Locale } from "./locale.js";
import { translate } from "./translate.js";

/**
 * 时间 / 数字的本地化：统一走 Intl，不自己拼月份和星期。
 * 只处理「本端能决定格式」的部分；Host 下发的文案仍原样显示。
 */
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function toMillis(value: number | Date | undefined): number | undefined {
  if (value === undefined) return undefined;
  const ms = value instanceof Date ? value.getTime() : value;
  if (!Number.isFinite(ms) || ms <= 0) return undefined;
  return ms < 1_000_000_000_000 ? ms * 1_000 : ms;
}

function sameDay(a: number, b: number): boolean {
  const left = new Date(a);
  const right = new Date(b);
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  );
}

/** 时:分（24 小时制，跟随语言习惯用 Intl）。 */
export function formatClock(value: number | Date | undefined, locale: Locale = DEFAULT_LOCALE): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  return new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(ms));
}

/** 月日（同年）；跨年给年月日。 */
export function formatMonthDay(value: number | Date | undefined, locale: Locale = DEFAULT_LOCALE, now = Date.now()): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  const date = new Date(ms);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return new Intl.DateTimeFormat(locale, {
    ...(sameYear ? {} : { year: "numeric" as const }),
    month: "short",
    day: "numeric",
  }).format(date);
}

/** 会话列表里的时间：当天给时刻，同年给月日，跨年给完整日期。 */
export function formatSessionTime(value: number | undefined, locale: Locale = DEFAULT_LOCALE, now = Date.now()): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  if (sameDay(ms, now)) return formatClock(ms, locale);
  return formatMonthDay(ms, locale, now);
}

/**
 * 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 更早给日期。
 * 纯函数，now 与 locale 都由调用方给，测试可以固定。
 */
export function formatRelativeTime(
  value: number | Date | undefined,
  now: number = Date.now(),
  locale: Locale = DEFAULT_LOCALE,
): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  const diff = now - ms;
  // 未来时间（例如下次执行）：说「N 后」而不是「刚刚」。
  if (diff < 0) {
    const ahead = -diff;
    if (ahead < MINUTE) return translate(locale, "time.inMoments");
    if (ahead < HOUR) return translate(locale, "time.inMinutes", { count: Math.floor(ahead / MINUTE) });
    if (ahead < DAY) return translate(locale, "time.inHours", { count: Math.floor(ahead / HOUR) });
    if (ahead < 7 * DAY) return translate(locale, "time.inDays", { count: Math.floor(ahead / DAY) });
    return formatMonthDay(ms, locale, now);
  }
  if (diff < MINUTE) return translate(locale, "time.justNow");
  if (diff < HOUR) return translate(locale, "time.minutesAgo", { count: Math.floor(diff / MINUTE) });
  if (diff < DAY && sameDay(ms, now)) return translate(locale, "time.hoursAgo", { count: Math.floor(diff / HOUR) });

  const yesterday = new Date(now);
  yesterday.setHours(0, 0, 0, 0);
  yesterday.setDate(yesterday.getDate() - 1);
  if (sameDay(ms, yesterday.getTime())) return translate(locale, "time.yesterday");
  if (diff < 7 * DAY) return translate(locale, "time.daysAgo", { count: Math.floor(diff / DAY) });
  return formatMonthDay(ms, locale, now);
}

/** 消息列表的时间分隔条：今天 / 昨天 带时刻，更早给日期 + 时刻。 */
export function formatDayLabel(value: number | undefined, now: number = Date.now(), locale: Locale = DEFAULT_LOCALE): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  const clock = formatClock(ms, locale);
  if (sameDay(ms, now)) return `${translate(locale, "time.today")} ${clock}`;
  const yesterday = new Date(now);
  yesterday.setHours(0, 0, 0, 0);
  yesterday.setDate(yesterday.getDate() - 1);
  if (sameDay(ms, yesterday.getTime())) return `${translate(locale, "time.yesterday")} ${clock}`;
  return `${formatMonthDay(ms, locale, now)} ${clock}`;
}

/** 时长（运行时间等）：分钟 / 小时 / 天，走 Intl 单位。 */
export function formatDuration(ms: number | undefined, locale: Locale = DEFAULT_LOCALE): string {
  if (ms === undefined || !Number.isFinite(ms) || ms < 0) return "";
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return new Intl.NumberFormat(locale, { style: "unit", unit: "minute", maximumFractionDigits: 0 }).format(minutes);
  const hours = ms / 3_600_000;
  if (hours < 24) return new Intl.NumberFormat(locale, { style: "unit", unit: "hour", maximumFractionDigits: 1 }).format(hours);
  return new Intl.NumberFormat(locale, { style: "unit", unit: "day", maximumFractionDigits: 1 }).format(hours / 24);
}

/** 文件大小：小于 1KB 给字节，其余给 KB / MB（Intl 单位格式化）。 */
export function formatBytes(bytes: number | undefined, locale: Locale = DEFAULT_LOCALE): string {
  if (bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return new Intl.NumberFormat(locale, { style: "unit", unit: "byte", maximumFractionDigits: 0 }).format(bytes);
  const kilobytes = bytes / 1024;
  if (kilobytes < 1024) return new Intl.NumberFormat(locale, { style: "unit", unit: "kilobyte", maximumFractionDigits: 1 }).format(kilobytes);
  return new Intl.NumberFormat(locale, { style: "unit", unit: "megabyte", maximumFractionDigits: 1 }).format(kilobytes / 1024);
}

/** 数字：按语言分组（例如 1,234 / 1 234）。 */
export function formatNumber(value: number, locale: Locale = DEFAULT_LOCALE, options?: Intl.NumberFormatOptions): string {
  if (!Number.isFinite(value)) return "";
  return new Intl.NumberFormat(locale, options).format(value);
}
