/**
 * i18n 断言：词条完整度、逐条回落、语言解析、相对时间、以及「源码里没有硬编码中文」。
 *
 * 最后一项是扫描 `src/host/*.tsx`：去掉注释后仍然出现 CJK，就说明有文案没走 t()。
 * Host 下发的文案（设置条目、错误原因）本来就只在运行时出现，不会写进源码。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync, readdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".i18n-check.mjs");

const ENTRY = `
export { MESSAGES, ZH, EN, JA, KO, RU, type MessageKey } from "./src/i18n/messages.ts";
export { formatMessage, translate, createTranslator, resolveTemplate } from "./src/i18n/translate.ts";
export { resolveLocale, matchLocale, isLocale, DEFAULT_LOCALE, LOCALES } from "./src/i18n/locale.ts";
export { formatRelativeTime, formatDayLabel, formatSessionTime, formatNumber } from "./src/i18n/format.ts";
`;

let failures = 0;
const assert = (condition, message) => {
  if (!condition) {
    failures += 1;
    console.error(`✗ ${message}`);
    return false;
  }
  console.log(`✓ ${message}`);
  return true;
};

async function load() {
  await build({
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "i18n-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    logLevel: "warning",
    outfile: TEMP,
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();

/** 去掉块注释与行注释（保留换行，便于报行号）。 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/[^\n]*/g, (match, prefix) => prefix + " ".repeat(match.length - prefix.length));
}

try {
  // ── 1. 词条表完整性 ─────────────────────────────────────────
  const zhKeys = Object.keys(mod.ZH).sort();
  const enKeys = Object.keys(mod.EN).sort();
  assert(zhKeys.length > 100, `中文词条 ${zhKeys.length} 条`);
  assert(JSON.stringify(zhKeys) === JSON.stringify(enKeys), "中英词条键完全对齐");
  const emptyZh = zhKeys.filter((key) => mod.ZH[key].trim().length === 0);
  const emptyEn = enKeys.filter((key) => mod.EN[key].trim().length === 0);
  assert(emptyZh.length === 0 && emptyEn.length === 0, "没有空词条");
  const untranslated = enKeys.filter((key) => mod.EN[key] === mod.ZH[key] && /[\u4e00-\u9fff]/.test(mod.EN[key]));
  assert(untranslated.length === 0, `英文词条里没有残留中文${untranslated.length === 0 ? "" : `：${untranslated.join(",")}`}`);

  // ── 2. ja / ko / ru：键集与 zh 完全一致、无空串、真的翻译了 ──
  const DICTS = { ja: mod.JA, ko: mod.KO, ru: mod.RU };
  for (const [locale, dict] of Object.entries(DICTS)) {
    const keys = Object.keys(dict).sort();
    const missing = zhKeys.filter((key) => !(key in dict));
    const extra = keys.filter((key) => !(key in mod.ZH));
    assert(
      missing.length === 0 && extra.length === 0 && keys.length === zhKeys.length,
      `${locale} 键集与 zh 完全一致（${keys.length} 条${missing.length === 0 ? "" : `，缺：${missing.join(",")}`}${extra.length === 0 ? "" : `，多：${extra.join(",")}`}）`,
    );
    const empty = keys.filter((key) => dict[key].trim().length === 0);
    assert(empty.length === 0, `${locale} 没有空词条${empty.length === 0 ? "" : `：${empty.join(",")}`}`);
    // 占位符不能丢也不能多：{count} 这类必须原样保留。
    const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
    const badSlots = zhKeys.filter((key) => slots(dict[key]) !== slots(mod.ZH[key]));
    assert(badSlots.length === 0, `${locale} 占位符与 zh 一致${badSlots.length === 0 ? "" : `：${badSlots.join(",")}`}`);
  }

  // 抽查若干条：确实换成了目标语言，而不是把英文原样抄了一份。
  const SPOT_CHECK = [
    "common.cancel",
    "common.retry",
    "composer.send",
    "sessions.rename",
    "settings.title",
    "memory.search",
    "approval.approve",
    "time.justNow",
    "login.submit",
    "common.loadMore",
  ];
  for (const [locale, dict] of Object.entries(DICTS)) {
    const sameAsEn = SPOT_CHECK.filter((key) => dict[key] === mod.EN[key]);
    assert(
      sameAsEn.length === 0,
      `${locale} 抽查 ${SPOT_CHECK.length} 条都不等于英文${sameAsEn.length === 0 ? "" : `：${sameAsEn.join(",")}`}`,
    );
    // 抽查的每一条都直接用自己的词条，不会被 en 顶掉。
    const notOwn = SPOT_CHECK.filter((key) => mod.translate(locale, key) !== dict[key]);
    assert(notOwn.length === 0, `${locale} 已翻译的词条优先用自己的${notOwn.length === 0 ? "" : `：${notOwn.join(",")}`}`);
  }

  // 回落只在「某一条缺失」时发生，而且只回落那一条，不整本词典换掉。
  const partialJa = { ...mod.JA };
  delete partialJa["common.close"];
  const partialDicts = { ...mod.MESSAGES, ja: partialJa };
  assert(
    mod.resolveTemplate("ja", "common.close", partialDicts) === mod.EN["common.close"],
    "ja 缺某一条时，那一条回落英文",
  );
  assert(
    mod.resolveTemplate("ja", "common.cancel", partialDicts) === mod.JA["common.cancel"],
    "ja 缺某一条时，其余词条仍用自己的",
  );
  assert(mod.translate("ja", "common.cancel") === mod.JA["common.cancel"], "translate 走 ja 自己的词条");
  assert(mod.translate("ko", "settings.title") === mod.KO["settings.title"], "translate 走 ko 自己的词条");
  assert(mod.translate("ru", "composer.send") === mod.RU["composer.send"], "translate 走 ru 自己的词条");
  for (const locale of mod.LOCALES) {
    const text = mod.translate(locale, "chat.jumpLatest");
    assert(text.length > 0 && text !== "chat.jumpLatest", `${locale} 不会露出 key 名`);
  }

  // ── 3. 占位符插值 ───────────────────────────────────────────
  assert(mod.formatMessage("第 {count} 条", { count: 3 }) === "第 3 条", "占位符被替换");
  assert(mod.formatMessage("没有参数的占位符 {x}", {}) === "没有参数的占位符 {x}", "缺参数时占位符原样保留");
  assert(mod.translate("zh", "chat.selectedCount", { count: 2 }) === "已选 2 条", "词条 + 插值");
  assert(mod.translate("en", "chat.selectedCount", { count: 2 }) === "2 selected", "英文插值");

  // ── 4. 语言解析 ─────────────────────────────────────────────
  assert(mod.resolveLocale("en", ["zh-CN"]) === "en", "设置里指定了就优先");
  assert(mod.resolveLocale("system", ["ja-JP", "en"]) === "ja", "system 按浏览器语言列表首个可识别项");
  assert(mod.resolveLocale("system", ["fr-FR"]) === "zh", "都匹配不到回落 zh");
  assert(mod.resolveLocale(undefined, []) === mod.DEFAULT_LOCALE, "没有语言信息时回落默认");
  assert(mod.resolveLocale("system", ["en-US"]) === "en", "区域标签取主语言");
  assert(mod.matchLocale("ko_KR") === "ko" && mod.matchLocale("xx") === undefined, "matchLocale 处理下划线与未知语言");
  assert(mod.resolveLocale("nonsense", ["en-GB"]) === "en", "不认识的值当作 system 处理");

  // ── 5. 相对时间（zh / en 各分支）────────────────────────────
  const now = new Date(2026, 9, 30, 15, 0, 0).getTime();
  const at = (minutesAgo) => now - minutesAgo * 60_000;
  assert(mod.formatRelativeTime(at(0.2), now, "zh") === "刚刚", "zh：不到一分钟是「刚刚」");
  assert(mod.formatRelativeTime(at(0.2), now, "en") === "Just now", "en：不到一分钟是 Just now");
  assert(mod.formatRelativeTime(at(12), now, "zh") === "12 分钟前", "zh：分钟前");
  assert(mod.formatRelativeTime(at(12), now, "en") === "12 min ago", "en：分钟前");
  assert(mod.formatRelativeTime(at(180), now, "zh") === "3 小时前", "zh：小时前");
  assert(mod.formatRelativeTime(at(180), now, "en") === "3 h ago", "en：小时前");
  const yesterday = new Date(2026, 9, 29, 20, 0, 0).getTime();
  assert(mod.formatRelativeTime(yesterday, now, "zh") === "昨天", "zh：昨天");
  assert(mod.formatRelativeTime(yesterday, now, "en") === "Yesterday", "en：昨天");
  assert(mod.formatRelativeTime(at(3 * 24 * 60), now, "zh") === "3 天前", "zh：几天前");
  assert(mod.formatRelativeTime(at(3 * 24 * 60), now, "en") === "3 days ago", "en：几天前");
  const longAgo = new Date(2026, 5, 1, 9, 0, 0).getTime();
  assert(/6月1日/.test(mod.formatRelativeTime(longAgo, now, "zh")), "zh：超过一周给日期");
  assert(/Jun/.test(mod.formatRelativeTime(longAgo, now, "en")), "en：超过一周给日期（Intl 缩写）");
  assert(mod.formatRelativeTime(undefined, now, "zh") === "", "没有时间戳返回空串");

  // ── 6. 日期/数字走 Intl ─────────────────────────────────────
  const sample = new Date(2026, 9, 28, 14, 25, 0).getTime();
  assert(mod.formatNumber(1234.5, "en", { maximumFractionDigits: 1 }) === "1,234.5", "数字本地化（en 分组）");
  assert(mod.formatDayLabel(sample, now, "zh").startsWith("10月28日"), "分隔条日期（zh）");
  assert(/Oct 28/.test(mod.formatDayLabel(sample, now, "en")), "分隔条日期（en）");
  assert(mod.formatSessionTime(sample, "zh", now) === "10月28日", "会话时间（zh）");

  // ── 7. 源码里没有硬编码中文 ─────────────────────────────────
  const hostDir = join(ROOT, "src", "host");
  const offenders = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx"))) {
    const source = stripComments(readFileSync(join(hostDir, file), "utf8"));
    const lines = source.split(/\r?\n/);
    lines.forEach((line, index) => {
      if (/[\u4e00-\u9fff]/.test(line)) offenders.push(`${file}:${index + 1} ${line.trim().slice(0, 60)}`);
    });
  }
  assert(offenders.length === 0, `src/host/*.tsx 里没有硬编码中文${offenders.length === 0 ? "" : `：\n  ${offenders.join("\n  ")}`}`);

  if (failures > 0) throw new Error(`i18n 断言未通过（${failures} 条）`);
  console.log("\ni18n 断言全部通过（词条对齐 / 逐条回落 / 插值 / 语言解析 / 相对时间 / 无硬编码中文）。");
} finally {
  await rm(TEMP, { force: true });
}
