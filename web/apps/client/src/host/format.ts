import { formatDayLabel, formatSessionTime as formatSessionTimeIntl } from "../i18n/format.js";
import { DEFAULT_LOCALE, type Locale } from "../i18n/locale.js";

/**
 * 聊天页的时间格式化：实现放在 `src/i18n/format.ts`（走 Intl + 词条表），
 * 这里只保留分桶用的纯函数和几个转发，避免调用方散落两套格式化逻辑。
 */

/** Host 下发的是 epoch 毫秒；对秒级取整的历史数据做一次归一。 */
function toMillis(value: number | undefined): number | undefined {
  if (value === undefined || !Number.isFinite(value) || value <= 0) return undefined;
  return value < 1_000_000_000_000 ? value * 1_000 : value;
}

/** 会话列表里的时间：当天给时刻，同年给月日，跨年给完整日期。 */
export function formatSessionTime(value: number | undefined, locale: Locale = DEFAULT_LOCALE): string {
  return formatSessionTimeIntl(value, locale);
}

/**
 * 本地时区的「天」分桶键。跨天判定必须按本地日历日，不能用 UTC（否则 00:30 的消息
 * 会被算到前一天）。时间戳不可用时返回 undefined，调用方据此跳过分隔条。
 */
export function dayKey(value: number | undefined): string | undefined {
  const ms = toMillis(value);
  if (ms === undefined) return undefined;
  const date = new Date(ms);
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

/**
 * 消息流分隔条文案：今天 / 昨天带时刻，更早给日期 + 时刻（都由 Intl 按语言格式化）。
 */
export function formatDaySeparator(value: number | undefined, now: number = Date.now(), locale: Locale = DEFAULT_LOCALE): string {
  return formatDayLabel(value, now, locale);
}

/** 语音时长：「12″」，超过一分钟走「1′05″」。未知时长返回空串，由调用方给本地化文案。 */
export function formatVoiceDuration(durationMs: number | undefined): string {
  if (durationMs === undefined || !Number.isFinite(durationMs) || durationMs <= 0) return "";
  const totalSeconds = Math.max(1, Math.round(durationMs / 1_000));
  if (totalSeconds < 60) return `${totalSeconds}″`;
  return `${Math.floor(totalSeconds / 60)}′${String(totalSeconds % 60).padStart(2, "0")}″`;
}

/** 工具耗时：10 秒以下给一位小数（0.3s / 1.2s），一分钟以上拆成分秒。 */
export function formatToolDuration(durationMs: number | undefined): string | undefined {
  if (durationMs === undefined || !Number.isFinite(durationMs) || durationMs < 0) return undefined;
  if (durationMs < 10_000) return `${(durationMs / 1_000).toFixed(1)}s`;
  const seconds = Math.round(durationMs / 1_000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m${String(seconds % 60).padStart(2, "0")}s`;
}
