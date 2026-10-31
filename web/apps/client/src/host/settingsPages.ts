import type { HostSettingValue } from "../api.js";

/**
 * 设置子页的归页规则。
 *
 * Host 的 `values[]` 是一张扁平的「键 → 当前值」表，没有分组字段；分类页点进去的
 * 是目录条目（`entry.id`）。所以这里用两张表把两边接上：
 *  - `entryIds`：哪个目录条目对应哪一页（`appearance` / `chat` / `tts` / `memory` …）；
 *  - `keyTokens`：值键归一后（去掉 `.` `_` `-`、小写）包含任一 token 即归入本页。
 *
 * 归页只看**键的形状**，不看文案：行标题一律优先用 Host 已本地化的目录标题，
 * 目录里没有对应条目时用键派生，绝不在 Web 侧维护中文文案表。
 * 匹配按数组顺序取第一个命中页，所以出现跨页歧义的键（如 `memoryAutoSave`）时，
 * 把更具体的页放在前面即可调整归属。
 */
export type SettingsPageId = "appearance" | "chat" | "voice" | "memory" | "general";

export interface SettingsPageSpec {
  readonly id: SettingsPageId;
  readonly entryIds: readonly string[];
  readonly keyTokens: readonly string[];
}

export const SETTINGS_PAGE_SPECS: readonly SettingsPageSpec[] = [
  {
    id: "appearance",
    entryIds: ["appearance"],
    keyTokens: ["thememode", "themeid", "fontsize", "fontscale", "textscale", "appearance"],
  },
  {
    id: "chat",
    entryIds: ["chat"],
    keyTokens: [
      "entertosend",
      "sendkey",
      "sendbutton",
      "showtoolcall",
      "toolcall",
      "showtimestamp",
      "timestamp",
      "stream",
      "thinking",
      "chatpreference",
    ],
  },
  {
    id: "voice",
    entryIds: ["tts", "voice", "speech"],
    keyTokens: ["tts", "voice", "speechrate", "speechpitch", "speechspeed", "pronunciation"],
  },
  {
    id: "memory",
    entryIds: ["memory"],
    keyTokens: ["memory", "autosave"],
  },
  {
    // 目录里暂时没有独立的「通用」入口；Host 加上 general 后自动改走它。
    // `about` 是对当前版本能落到这一页的兜底入口（语言 / 模型是否已配置本来就在这类页）。
    id: "general",
    entryIds: ["general", "common", "about"],
    keyTokens: ["language", "locale", "modelconfigured", "modelready", "configured", "general"],
  },
];

/** 键归一：`chatPreferences.enterToSend` → `chatpreferencesentertosend`。 */
export function normalizeSettingKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, "");
}

export function pageForEntry(entryId: string): SettingsPageSpec | undefined {
  const id = entryId.trim().toLowerCase();
  return SETTINGS_PAGE_SPECS.find((spec) => spec.entryIds.includes(id));
}

export function pageForValueKey(key: string): SettingsPageSpec | undefined {
  const normalized = normalizeSettingKey(key);
  if (normalized.length === 0) return undefined;
  return SETTINGS_PAGE_SPECS.find((spec) => spec.keyTokens.some((token) => normalized.includes(token)));
}

/** 页面要显示的值：按 Host 下发的顺序保留，不重排。 */
export function valuesForPage(
  spec: SettingsPageSpec,
  values: readonly HostSettingValue[] | undefined,
): readonly HostSettingValue[] {
  if (values === undefined) return [];
  return values.filter((value) => pageForValueKey(value.key)?.id === spec.id);
}

/**
 * 行标题的兜底：Host 没下发这条值的标题时，从键派生一个可读词。
 * 只做大小写与分隔符切分，不做翻译（避免 Web 侧维护一份会与 Host 漂移的文案表）。
 */
export function humanizeSettingKey(key: string): string {
  const tail = key.split(".").pop() ?? key;
  const spaced = tail.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").trim();
  if (spaced.length === 0) return key;
  return spaced
    .split(/\s+/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

/**
 * 只读键：`values[]` 不区分「能不能写」，这几类按产品约定只看不给改——
 * 音色名与「模型是否已配置」都是回显型信息，写成开关/输入框反而是错的。
 */
const READ_ONLY_KEY_TOKENS: readonly string[] = ["modelconfigured", "modelready", "voicename", "voiceid", "voiceprofile"];

export function isReadOnlyKey(key: string): boolean {
  const normalized = normalizeSettingKey(key);
  return READ_ONLY_KEY_TOKENS.some((token) => normalized.includes(token));
}

export function readBoolean(value: unknown): boolean {
  return value === true;
}

/**
 * 一条值的行标题：目录里 toggleKey 命中的 Host 标题 → Host 若下发的 label → 键派生。
 * 子页渲染与搜索索引共用这一份，避免同一个值在两处显示成不同名字。
 */
export function resolveValueTitle(
  value: HostSettingValue,
  titlesBySettingKey: ReadonlyMap<string, string>,
): string {
  const fromEntry = titlesBySettingKey.get(value.key);
  if (fromEntry !== undefined && fromEntry.length > 0) return fromEntry;
  const described = (value as { readonly label?: unknown }).label;
  if (typeof described === "string" && described.length > 0) return described;
  return humanizeSettingKey(value.key);
}

export function readNumber(value: unknown, fallback = 0): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim().length > 0) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

export function readText(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return "";
}

/** 数字行的步长：整数按 1，带小数的（如语速 1.0 / 0.9）按 0.1。 */
export function numberStepFor(current: number): number {
  return Number.isInteger(current) ? 1 : 0.1;
}

/** 步进结果，向下不越过 0（Host 侧再做一次类型与范围校验）。 */
export function stepNumber(current: number, direction: 1 | -1): number {
  const next = Number((current + numberStepFor(current) * direction).toFixed(2));
  return next < 0 ? 0 : next;
}
