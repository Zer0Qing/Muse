import { createContext, useContext, useMemo, type ReactNode } from "react";
import { DEFAULT_LOCALE, type Locale } from "./locale.js";
import { createTranslator, type Translate } from "./translate.js";

export interface I18nValue {
  readonly locale: Locale;
  readonly t: Translate;
}

/**
 * 默认值就是中文：没套 Provider 的地方（单测里直接渲染组件）仍然按 zh 渲染，
 * 不依赖浏览器环境。真实界面由 HostChat 用解析后的语言套一层 Provider。
 */
const FALLBACK: I18nValue = { locale: DEFAULT_LOCALE, t: createTranslator(DEFAULT_LOCALE) };

const I18nContext = createContext<I18nValue>(FALLBACK);

export function I18nProvider({ locale, children }: { readonly locale: Locale; readonly children: ReactNode }) {
  const value = useMemo<I18nValue>(() => ({ locale, t: createTranslator(locale) }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  return useContext(I18nContext);
}
