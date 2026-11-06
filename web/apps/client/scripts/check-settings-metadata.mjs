/**
 * S1 设置元数据的断言：高级项默认收起、依赖未满足时说明而不是消失、
 * 控件按元数据生成（滑杆 / − ＋ / 单选 / 开关），以及老 Host 缺字段时的退化行为。
 *
 * 组件用 renderToStaticMarkup 渲染；展开/收起这类交互按源码断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-metadata-check.mjs");

const ENTRY = `
export { SettingsValueCard } from "./src/host/SettingsValuePage.tsx";
export { humanizeSettingKey, pageForValueKey, SETTINGS_PAGE_SPECS } from "./src/host/settingsPages.ts";
export { dependencyMet, isAdvanced, splitAdvanced, widgetOf } from "./src/host/settingsMetadata.ts";
export { ZH, EN, JA, KO, RU } from "./src/i18n/messages.ts";
export { createElement } from "react";
export { renderToStaticMarkup } from "react-dom/server";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-metadata-entry.tsx", loader: "tsx" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    loader: { ".css": "empty" },
    external: ["react", "react-dom", "react-dom/server"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const render = (element) => mod.renderToStaticMarkup(element);

const PAGE_SOURCE = readFileSync(join(ROOT, "src", "host", "SettingsValuePage.tsx"), "utf8");
const API = readFileSync(join(ROOT, "src", "api.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

// 值必须是**真会映射到某个子页的键**：valuesForPage 按全局键→页规则筛，
// 编一个键名只会得到一张空卡片，然后所有渲染断言都在测「空」。
const CANDIDATE_KEYS = [
  "themeMode",
  "fontSizeScale",
  "liquidGlassMode",
  "liquidGlassStrength",
  "dynamicColor",
  "highContrast",
  "chatPreferences.enterToSend",
  "chatPreferences.showTimestamp",
  "chatPreferences.forceReasoning",
  "chatPreferences.reasoningLevel",
  "mediaConfig.ttsEngine",
  "mediaConfig.ttsSpeechRate",
  "language",
];
const pageBuckets = new Map();
for (const key of CANDIDATE_KEYS) {
  const spec = mod.pageForValueKey(key);
  if (spec === undefined) continue;
  pageBuckets.set(spec.id, [...(pageBuckets.get(spec.id) ?? []), key]);
}
const [pageId, pageKeys] = [...pageBuckets.entries()].sort((a, b) => b[1].length - a[1].length)[0];
const page = mod.SETTINGS_PAGE_SPECS.find((spec) => spec.id === pageId);
assert(pageKeys.length >= 3, `找到一页有 ≥3 个键来演三种角色（实际 ${pageKeys.length} 个：${pageKeys.join(", ")}）`);

// 角色按真实类型分：一个普通开关、一个作为依赖来源的开关、一个高级数值项。
// 依赖的候选值必须与来源项的实际取值同型（布尔就是 "true"），
// 否则「依赖已满足」这一档根本测不到 —— 上一版就是这么悄悄全红的。
const plainKey = pageKeys[0];
const dependsKey = pageKeys[1];
const strengthKey = pageKeys[2];
const humanized = (key) => mod.humanizeSettingKey(key);

const strengthValue = {
  key: strengthKey,
  type: "number",
  value: "40",
  min: 0,
  max: 100,
  step: 5,
  advanced: true,
  widget: "slider",
  dependsOn: { key: dependsKey, values: ["true"] },
};
const dependsRow = (value) => ({ key: dependsKey, type: "boolean", value, widget: "switch" });
const plainRow = { key: plainKey, type: "boolean", value: "true", widget: "switch" };

const snapshotWith = (values) => ({ groups: [], toggles: [], display: {}, writableKeys: [], values });
const card = (values, focusKey) =>
  render(
    mod.createElement(mod.SettingsValueCard, {
      page,
      snapshot: snapshotWith(values),
      onWrite: () => undefined,
      ...(focusKey === undefined ? {} : { focusKey }),
    }),
  );

try {
  // ── 1. 纯函数口径 ──────────────────────────────────────────
  assert(mod.isAdvanced(strengthValue) === true, "advanced=true 判为高级项");
  assert(mod.isAdvanced(plainRow) === false, "没下发 advanced 时按普通项（老 Host 不会突然全被折叠）");
  assert(mod.widgetOf(strengthValue) === "slider", "Host 给的 widget 优先");
  assert(mod.widgetOf({ key: "k", type: "number", value: "1", min: 0, max: 4, step: 1 }) === "slider", "缺 widget 时按范围派生滑杆");
  assert(mod.widgetOf({ key: "k", type: "number", value: "1" }) === "number", "没有范围仍是输入框");
  assert(mod.widgetOf({ key: "k", type: "boolean", value: "true" }) === "switch", "布尔派生成开关");
  assert(mod.widgetOf({ key: "k", type: "enum", value: "a", options: [{ value: "a" }, { value: "b" }] }) === "select", "多候选枚举派生成单选");

  const lookup = (key) => (key === dependsKey ? "false" : undefined);
  assert(mod.dependencyMet(strengthValue, lookup) === false, "依赖项关着时该项未满足");
  assert(mod.dependencyMet(strengthValue, () => "true") === true, "依赖项开着时满足");
  assert(mod.dependencyMet(strengthValue, () => "TRUE") === true, "取值比较不区分大小写");
  assert(mod.dependencyMet(strengthValue, () => undefined) === true, "读不到依赖项时按满足处理：宁可多显示一行也不凭空吃掉一个开关");
  assert(mod.dependencyMet({ key: "k", type: "number", value: "1" }, () => undefined) === true, "没有依赖恒为满足");
  const split = mod.splitAdvanced([plainRow, strengthValue, dependsRow("true")]);
  assert(split.advanced.length === 1 && split.plain.length === 2, "高级/普通分组正确且不打乱顺序");

  // ── 2. 渲染：默认收起 ──────────────────────────────────────
  const collapsed = card([plainRow, dependsRow("true"), strengthValue]);
  assert(collapsed.includes(humanized(plainKey)), "普通项照常渲染（标题由键派生）");
  assert(collapsed.includes("高级设置"), "有高级项时出现折叠条");
  assert(collapsed.includes(`高级设置（1 项）`), "折叠条报得出条数");
  assert(!collapsed.includes('type="range"'), "默认收起时不渲染滑杆（高级项不许在首屏被误改）");
  assert(collapsed.includes('aria-expanded="false"'), "折叠条把状态给读屏");

  const expanded = card([plainRow, dependsRow("true"), strengthValue], strengthKey);
  assert(expanded.includes('type="range"'), "搜索直达命中高级项时必须自动展开");
  assert(expanded.includes('aria-expanded="true"'), "展开状态同样可达");
  assert(expanded.includes('min="0"') && expanded.includes('max="100"') && expanded.includes('step="5"'), "滑杆的范围来自 Host 元数据而不是前端抄一份");
  assert(/aria-label="[^"]+"/.test(expanded), "滑杆有可读标签（不能只有滑块没有名字）");
  assert(expanded.includes("host-setting-highlight"), "直达的那一行被高亮");

  // ── 3. 渲染：依赖未满足 ────────────────────────────────────
  const blocked = card([plainRow, dependsRow("false"), { ...strengthValue, advanced: false }]);
  assert(blocked.includes("需要先开启"), "依赖未满足时说明要先开哪一项");
  assert(blocked.includes(humanized(dependsKey)), "提示里点名的是依赖项的标题，不是裸键名");
  assert(!blocked.includes('type="range"'), "未满足依赖的行不给写入口（能点却没反应比不给控件更坏）");
  const unblocked = card([plainRow, dependsRow("true"), { ...strengthValue, advanced: false }]);
  assert(unblocked.includes('type="range"'), "依赖满足后同一行给出控件");

  // ── 4. 老 Host（完全没有元数据）──
  const legacy = card([plainRow, dependsRow("true"), { key: strengthKey, type: "number", value: "40" }]);
  assert(legacy.includes(humanized(plainKey)), "老 Host 的项仍按原样渲染（没有元数据也要出行）");
  assert(!legacy.includes("高级设置"), "没有元数据时不凭空造一个折叠条");
  assert(legacy.includes("host-stepper"), "没有范围的老数值项仍是 −/＋ 步进，不是一片空白");

  // ── 5. 接线与词条 ──────────────────────────────────────────
  assert(PAGE_SOURCE.includes("splitAdvanced(rows)") && PAGE_SOURCE.includes("dependencyMet(value, valueOf)"), "子页真的按元数据分组与判依赖");
  assert(PAGE_SOURCE.includes("blockedBy?: string | undefined") && PAGE_SOURCE.includes("blockedBy={blockedByOf(value)}"), "依赖提示沿 props 传到行");
  assert(PAGE_SOURCE.includes("Math.min(value.max, withMin)"), "步进按钮夹住上界（以前只夹 0，可以一路加出范围再被 Host 拒绝）");
  for (const needle of ["readonly advanced?:", "readonly widget?:", "readonly dependsOn?:", "readonly min?:", "readonly step?:"]) {
    assert(API.includes(needle), `api.ts 声明了 ${needle}`);
  }
  for (const name of ["ZH", "EN", "JA", "KO", "RU"]) {
    const dict = mod[name];
    for (const key of ["settings.advanced", "settings.advancedShow", "settings.advancedHide", "settings.advancedOpen", "settings.advancedClosed", "settings.dependencyHint"]) {
      assert(typeof dict[key] === "string" && dict[key].length > 0, `${name} 有 ${key}`);
    }
    assert(dict["settings.advanced"].includes("{count}"), `${name} 的高级条带数量占位符`);
    assert(dict["settings.dependencyHint"].includes("{title}"), `${name} 的依赖提示带标题占位符`);
  }
  assert(/\.host-advanced-toggle/.test(CSS) && /\.host-slider \{/.test(CSS), "折叠条与滑杆有样式");

  if (failures > 0) throw new Error(`设置元数据断言未通过（${failures} 条）`);
  console.log("\n设置元数据断言全部通过（分组 / 依赖 / 控件派生 / 老 Host 退化 / 五语词条）。");
} finally {
  await rm(TEMP, { force: true });
}
