/**
 * 新命令的能力协商。
 *
 * Host 的能力位命名还没定稿，这里对同一能力接受多个写法（`assistants_detail` /
 * `assistant_detail`）；**没下发 capabilityFlags 的老 Host 一律返回 true**——先发命令试一次，
 * 真不支持时 Host 会回 `error`，页面再切到「当前版本暂不支持」的提示。
 */
import type { HostSystemCapabilities } from "../api.js";

export const ASSISTANT_DETAIL_FLAGS: readonly string[] = ["assistants_detail", "assistant_detail"];
export const MEMORY_FLAGS: readonly string[] = ["memory", "memory_list"];
export const KNOWLEDGE_FLAGS: readonly string[] = ["knowledge", "knowledge_base"];
export const TASK_FLAGS: readonly string[] = ["tasks", "scheduled_tasks"];
export const SYSTEM_STATUS_FLAGS: readonly string[] = ["system_status"];
export const ASSISTANT_WRITE_FLAGS: readonly string[] = ["assistant_write", "assistants_write"];

export function supportsFlag(flags: readonly string[] | undefined, tokens: readonly string[]): boolean {
  if (flags === undefined) return true;
  return tokens.some((token) => flags.includes(token));
}

/** Host 拒绝一个它没实现的命令时的错误文本，认几种常见写法。 */
export function isUnsupportedCommandError(message: string | undefined): boolean {
  if (message === undefined) return false;
  return /unsupported|unknown command|not implemented|not supported/i.test(message);
}

/** 平台专有能力的三种状态。 */
export type PlatformAvailability = "available" | "notEnabled" | "unavailable";

/**
 * 从 system.status.capabilities 推平台能力状态：
 * true → 可用；false → 未开启（设备支持但没开）；字段缺了 → 不可用。
 * 多字段的能力（如 UI 自动化）任一可用即算可用。
 */
export function platformAvailability(
  capabilities: HostSystemCapabilities | undefined,
  keys: readonly (keyof HostSystemCapabilities)[],
): PlatformAvailability {
  if (capabilities === undefined) return "unavailable";
  const values = keys.map((key) => capabilities[key]);
  if (values.some((value) => value === true)) return "available";
  if (values.some((value) => value === false)) return "notEnabled";
  return "unavailable";
}

/**
 * 目录条目 → 依赖的平台能力。
 *
 * Host 侧条目 id 还没完全定稿，这里同时容忍下划线与连字符、以及几个近义写法；
 * 匹配不上就不进平台页（继续走通用占位页）。
 */
const PLATFORM_ENTRY_KEYS: Readonly<Record<string, readonly (keyof HostSystemCapabilities)[]>> = {
  accessibility: ["accessibility"],
  accessibility_service: ["accessibility"],
  virtual_display: ["virtualDisplay"],
  virtualdisplay: ["virtualDisplay"],
  terminal: ["shell"],
  shell: ["shell"],
  notification_listener: ["notificationListener"],
  notificationlistener: ["notificationListener"],
  ui_automation: ["accessibility", "shizuku", "root"],
  uiautomation: ["accessibility", "shizuku", "root"],
};

export function platformKeysForEntry(entryId: string): readonly (keyof HostSystemCapabilities)[] | undefined {
  const normalized = entryId.trim().toLowerCase().replace(/[\s-]+/g, "_");
  return PLATFORM_ENTRY_KEYS[normalized] ?? PLATFORM_ENTRY_KEYS[normalized.replace(/_/g, "")];
}
