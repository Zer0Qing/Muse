import { useMemo } from "react";
import { normalizeSettingKey } from "./settingsPages.js";
import { navigatorLanguages, resolveLocale, type Locale } from "../i18n/locale.js";
import type { HostSettingsState } from "./useHostConnection.js";

/**
 * 界面语言：来自 `settings.snapshot.values` 里键含 `language` 的那一项（Host 下发
 * system/zh/en/ja/ko/ru）；没有就退回 `display.language`，再没有按浏览器语言。
 * `system` 的解析规则在 i18n/locale.ts（纯函数，可断言）。
 */
export function readLanguage(settings: HostSettingsState): unknown {
  const values = settings.snapshot?.values;
  if (values !== undefined) {
    for (const value of values) {
      if (normalizeSettingKey(value.key).includes("language")) return value.value;
    }
  }
  return settings.snapshot?.display.language;
}

export function useHostLocale(settings: HostSettingsState): Locale {
  const raw = readLanguage(settings);
  const languages = useMemo(() => navigatorLanguages(), []);
  return resolveLocale(raw, languages);
}
