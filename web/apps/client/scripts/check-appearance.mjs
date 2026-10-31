/**
 * 外观（主题 / 字号）的断言：三态主题的属性写入、四档字号映射、theme-color 更新、
 * 切换失败回滚、根属性与 CSS 选择器是否对得上。
 *
 * 纯逻辑直接跑；DOM 应用用一个极小的 target 替身（不引 jsdom）；
 * CSS 侧按源码结构断言选择器与倍率，并检查两块暗色声明没有漂。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".appearance-check.mjs");

const ENTRY = `
export {
  applyAppearance,
  FONT_SCALE_FACTOR,
  FONT_SCALES,
  readFontScale,
  readThemeMode,
  resolveScheme,
  THEME_MODES,
  themeColorFor,
} from "./src/host/appearance.ts";
export { readAppearance } from "./src/host/useAppearance.ts";
export { applySettingValue, settingValueOf } from "./src/host/settingsState.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "appearance-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    loader: { ".css": "empty" },
    external: ["react"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const PRIMITIVES = readFileSync(join(ROOT, "src", "components", "primitives.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const TOKEN_CSS = readFileSync(join(ROOT, "src", "design", "token-css.ts"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");

/** 取某个 `@media (…)` 的块体。 */
function mediaBlock(source, query) {
  const marker = `@media (${query}) {`;
  const start = source.indexOf(marker);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    else if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  return "";
}

/** 取某条规则（选择器 + `{`）的声明体。 */
function ruleBody(source, selector) {
  const start = source.indexOf(selector);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  const close = source.indexOf("}", open);
  if (open < 0 || close < 0) return "";
  return source.slice(open + 1, close);
}

/** 把声明体拆成 { "--ui-surface": "var(…)" } 这样的映射。 */
function declarations(body) {
  const map = {};
  for (const part of body.split(";")) {
    const index = part.indexOf(":");
    if (index < 0) continue;
    const name = part.slice(0, index).trim();
    const value = part.slice(index + 1).trim();
    if (name.length > 0) map[name] = value;
  }
  return map;
}

const settingsWith = (values, display = { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" }) => ({
  snapshot: { groups: [], toggles: [], display, writableKeys: [], values },
  loading: false,
});

try {
  // ── 1. 三态主题：属性写入 + 优先级 ─────────────────────────────
  const makeTarget = (tokens = {}) => {
    const dataset = {};
    const meta = { content: "initial" };
    return { dataset, meta, readToken: (name) => tokens[name] ?? "" };
  };

  const system = makeTarget({ "--muse-color-scheme-light-surface": "#FAFAF8", "--muse-color-scheme-dark-surface": "#000000" });
  mod.applyAppearance(system, "system", "medium", false);
  assert(system.dataset.theme === "system", "system 模式写 data-theme=system（交给媒体查询）");
  assert(system.dataset.fontScale === "medium", "字号档位写成 data-font-scale");

  const forcedDark = makeTarget({ "--muse-color-scheme-light-surface": "#FAFAF8", "--muse-color-scheme-dark-surface": "#000000" });
  mod.applyAppearance(forcedDark, "dark", "medium", false);
  assert(forcedDark.dataset.theme === "dark", "dark 强制深色（即使系统是浅色）");

  const forcedLight = makeTarget();
  mod.applyAppearance(forcedLight, "light", "medium", true);
  assert(forcedLight.dataset.theme === "light", "light 强制浅色（即使系统是深色）");

  assert(mod.resolveScheme("system", true) === "dark", "system + 系统深色 → dark");
  assert(mod.resolveScheme("system", false) === "light", "system + 系统浅色 → light");
  assert(mod.resolveScheme("light", true) === "light", "强制浅色不受系统影响");
  assert(mod.resolveScheme("dark", false) === "dark", "强制深色不受系统影响");

  assert(JSON.stringify(mod.THEME_MODES) === '["system","light","dark"]', "主题三档就是 system/light/dark");
  assert(mod.readThemeMode("DARK") === "dark" && mod.readThemeMode(" system ") === "system", "主题值大小写与空白都容忍");
  assert(mod.readThemeMode("blue") === undefined, "不认识的主题值当作没给");

  // CSS：深色两处声明（媒体查询 + 属性）且互相一致
  const mediaDark = declarations(ruleBody(mediaBlock(PRIMITIVES, "prefers-color-scheme: dark"), ':root:not([data-theme="light"])'));
  const attrDark = declarations(ruleBody(PRIMITIVES, ':root[data-theme="dark"]'));
  assert(Object.keys(attrDark).length > 10, `属性深色块有 ${Object.keys(attrDark).length} 条声明`);
  assert(
    JSON.stringify(mediaDark) === JSON.stringify(attrDark),
    "媒体查询与属性两处暗色声明逐条一致（防止漂移）",
  );
  assert(/var\(--muse-color-scheme-dark-surface\)/.test(attrDark["--ui-surface"] ?? ""), "强制深色指向 dark scheme 令牌");
  assert(PRIMITIVES.includes(':root:not([data-theme="light"])'), "系统深色时若强制浅色，媒体查询会被跳过");
  assert(CSS.includes(':root[data-theme="dark"] .host-scope { color-scheme: dark; }'), "host 作用域跟随强制深色");
  assert(CSS.includes(':root[data-theme="light"] .host-scope { color-scheme: light; }'), "host 作用域跟随强制浅色");

  // ── 2. theme-color 更新 ──────────────────────────────────────
  assert(system.meta.content === "#FAFAF8", `system（系统浅色）theme-color 取浅色 surface：${system.meta.content}`);
  assert(makeTarget && mod.themeColorFor("dark", (name) => (name.endsWith("dark-surface") ? "#000000" : "#FAFAF8")) === "#000000", "themeColorFor 按 scheme 取令牌");
  const darkMeta = makeTarget({ "--muse-color-scheme-dark-surface": "#000000", "--muse-color-scheme-light-surface": "#FAFAF8" });
  mod.applyAppearance(darkMeta, "dark", "medium", false);
  assert(darkMeta.meta.content === "#000000", "切换深色后 theme-color 跟着变成深色 surface");
  const noTokens = makeTarget();
  mod.applyAppearance(noTokens, "dark", "medium", false);
  assert(noTokens.meta.content === "initial", "读不到令牌时不动 meta（不留空串）");

  // ── 3. 四档字号映射 ─────────────────────────────────────────
  assert(JSON.stringify(mod.FONT_SCALES) === '["small","medium","large","xlarge"]', "四档就是 small/medium/large/xlarge");
  assert(mod.FONT_SCALE_FACTOR.small === 0.9 && mod.FONT_SCALE_FACTOR.medium === 1, "小档 0.9、中档 1.0");
  assert(mod.FONT_SCALE_FACTOR.large === 1.1 && mod.FONT_SCALE_FACTOR.xlarge === 1.2, "大档 1.1、特大 1.2");
  assert(mod.readFontScale("xlarge") === "xlarge" && mod.readFontScale("LARGE") === "large", "字号档位名大小写容忍");
  assert(mod.readFontScale("1") === "medium", "数字 1 归一成 medium");
  assert(mod.readFontScale(0.9) === "small" && mod.readFontScale("1.2") === "xlarge", "数字倍率归一到最近的档");
  assert(mod.readFontScale("huge") === undefined && mod.readFontScale(0) === undefined, "不认识的倍率当作没给");

  for (const scale of ["small", "medium", "large", "xlarge"]) {
    assert(CSS.includes(`:root[data-font-scale="${scale}"]`), `CSS 为 ${scale} 提供了 data-font-scale 选择器`);
  }
  const largeRule = declarations(ruleBody(CSS, ':root[data-font-scale="large"]'));
  assert(largeRule["--text-scale"] === "1.1", "large 档把 --text-scale 设成 1.1");
  assert(largeRule["--host-bubble-boost"] === "1.08", "large 档顺带把气泡放宽 8%");
  const xlargeRule = declarations(ruleBody(CSS, ':root[data-font-scale="xlarge"]'));
  assert(xlargeRule["--host-bubble-boost"] === "1.16", "xlarge 档放宽 16%（0.7 × 1.16 ≈ 0.81 仍不出屏）");

  // 排版令牌真的乘了倍率，间距令牌没被整体缩放
  assert(/calc\(\$\{value\}px \* var\(--text-scale, 1\)\)/.test(TOKEN_CSS), "字号 / 行高令牌乘 --text-scale");
  assert(/key !== "fontSize" && key !== "lineHeight"/.test(TOKEN_CSS), "只缩放 fontSize / lineHeight，不动间距");
  assert(/max-width: calc\(100% \* var\(--muse-component-bubble-max-width-ratio\) \* var\(--host-bubble-boost\)\)/.test(CSS), "气泡最大宽随字号放宽");
  assert(/--host-page: calc\(var\(--muse-space-screen\) \* var\(--text-scale\)\)/.test(CSS), "页边距随字号按比例调整");
  assert(/padding: calc\(var\(--muse-component-bubble-inner-padding-y\) \* var\(--text-scale\)\)/.test(CSS), "气泡内边距随字号按比例调整（不贴边）");

  // ── 4. 从设置值读外观 + 失败回滚 ─────────────────────────────
  const darkSnapshot = settingsWith([
    { key: "appearance.themeMode", type: "enum", value: "dark" },
    { key: "appearance.fontSizeScale", type: "enum", value: "xlarge" },
  ]);
  const read0 = mod.readAppearance(darkSnapshot);
  assert(read0.mode === "dark" && read0.scale === "xlarge", "从 values 读到主题与字号（键带前缀也能认）");

  const legacy = settingsWith(undefined, { language: "zh", themeMode: "light", fontSizeScale: "1.1", themeId: "default" });
  const readLegacy = mod.readAppearance(legacy);
  assert(readLegacy.mode === "light" && readLegacy.scale === "large", "老 Host 只给 display 时也能读出来");

  const empty = mod.readAppearance({ loading: false });
  assert(empty.mode === "system" && empty.scale === "medium", "没有设置时退回 system + medium");

  // 乐观切换：values 一变主题就变；回滚后恢复
  const optimistic = mod.applySettingValue(darkSnapshot.snapshot, "appearance.themeMode", "light");
  assert(mod.readAppearance({ ...darkSnapshot, snapshot: optimistic }).mode === "light", "乐观写入后主题立刻变浅色");
  const rolledBack = mod.applySettingValue(optimistic, "appearance.themeMode", "dark");
  assert(mod.readAppearance({ ...darkSnapshot, snapshot: rolledBack }).mode === "dark", "回滚后主题恢复深色");
  assert(CHAT.includes("useAppearance(settings)"), "聊天壳把设置值接到外观上");
  assert(/requestSettings\(\)/.test(CHAT), "连上就取一次设置（不等打开设置页）");

  // ── 5. 自绘元素跟着 scheme 走（只用令牌） ────────────────────
  const tokenDriven = [
    [".host-lightbox", "--host-scrim"],
    [".host-voice-track", "--host-veil"],
    [".host-details", "--host-veil"],
    [".host-bubble-tools", "flex"],
  ];
  for (const [selector, needle] of tokenDriven) {
    const body = ruleBody(CSS, `${selector} {`);
    assert(body.length > 0 && body.includes(needle), `${selector} 走令牌（${needle}）`);
  }
  assert(
    /\.muse-skeleton \{[\s\S]{0,400}?var\(--ui-surface-variant\)/.test(PRIMITIVES),
    "骨架用令牌底色，深色下自动变",
  );

  if (failures > 0) throw new Error(`外观断言未通过（${failures} 条）`);
  console.log("\n外观断言全部通过（三态主题 / 四档字号 / theme-color / 回滚 / 联动）。");
} finally {
  await rm(TEMP, { force: true });
}
