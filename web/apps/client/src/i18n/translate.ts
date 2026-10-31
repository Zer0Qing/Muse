import { DEFAULT_LOCALE, type Locale } from "./locale.js";
import { MESSAGES, type MessageDict, type MessageKey } from "./messages.js";

export type TranslateParams = Readonly<Record<string, string | number>>;
export type Translate = (key: MessageKey, params?: TranslateParams) => string;

/** 占位符替换：`{count}` → 值；params 里没有的占位符原样留着（便于发现漏传）。 */
export function formatMessage(template: string, params?: TranslateParams): string {
  if (params === undefined) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}

/**
 * 按语言取模板，**逐条**回落：当前语言 → en → zh → key 名。
 *
 * 已经翻译过的语言一律用自己的词条（ja / ko / ru 现在都是全量词条），
 * 只有某一条缺失时才回落到 en——不是整本词典换成英文。
 * dicts 参数只为断言留出口（传一份缺词条的词典验证回落），界面一律用默认值。
 */
export function resolveTemplate(
  locale: Locale,
  key: MessageKey,
  dicts: Readonly<Record<string, MessageDict>> = MESSAGES,
): string {
  return dicts[locale]?.[key] ?? dicts["en"]?.[key] ?? dicts[DEFAULT_LOCALE]?.[key] ?? key;
}

export function translate(locale: Locale, key: MessageKey, params?: TranslateParams): string {
  return formatMessage(resolveTemplate(locale, key), params);
}

export function createTranslator(locale: Locale): Translate {
  return (key, params) => translate(locale, key, params);
}
