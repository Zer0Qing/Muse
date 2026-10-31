import { useEffect } from "react";
import type { HostSettingValue } from "../api.js";
import {
  applyAppearance,
  documentAppearanceTarget,
  readFontScale,
  readThemeMode,
  type FontScale,
  type ThemeMode,
} from "./appearance.js";
import { normalizeSettingKey } from "./settingsPages.js";
import { useMediaQuery } from "./useMediaQuery.js";
import type { HostSettingsState } from "./useHostConnection.js";

export interface AppearanceState {
  readonly mode: ThemeMode;
  readonly scale: FontScale;
}

/** 在 values 里按「键里包含这些片段」找一个值（键可能是 appearance.themeMode 这种带前缀的）。 */
function valueOfToken(values: readonly HostSettingValue[] | undefined, tokens: readonly string[]): unknown {
  if (values === undefined) return undefined;
  for (const value of values) {
    const key = normalizeSettingKey(value.key);
    if (tokens.some((token) => key.includes(token))) return value.value;
  }
  return undefined;
}

/**
 * 从设置快照里读出外观状态：优先 `values[]`，没有就退回 `display`（老 Host 只给后者）。
 * 纯函数，便于断言「四档映射」「不认识的值退回默认」。
 */
export function readAppearance(settings: HostSettingsState): AppearanceState {
  const values = settings.snapshot?.values;
  const display = settings.snapshot?.display;
  const mode = readThemeMode(valueOfToken(values, ["thememode"])) ?? readThemeMode(display?.themeMode) ?? "system";
  const scale =
    readFontScale(valueOfToken(values, ["fontsizescale", "fontscale", "textscale"])) ??
    readFontScale(display?.fontSizeScale) ??
    "medium";
  return { mode, scale };
}

/**
 * 把外观写到根元素上。
 *
 * 写入是「跟着设置值走」的：设置页写入是乐观更新，`values` 一变这里立刻重跑 →
 * 主题当场就切；Host 拒绝时设置层把旧值写回去 → 这里再跑一遍自动还原。
 * 系统主题还要跟着 `prefers-color-scheme` 变，所以订阅了那条媒体查询。
 */
export function useAppearance(settings: HostSettingsState): AppearanceState {
  const { mode, scale } = readAppearance(settings);
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");

  useEffect(() => {
    if (typeof document === "undefined") return;
    applyAppearance(documentAppearanceTarget(document), mode, scale, prefersDark);
  }, [mode, scale, prefersDark]);

  return { mode, scale };
}
