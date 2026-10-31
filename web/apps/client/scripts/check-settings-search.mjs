/**
 * 设置搜索的断言：本地过滤、面包屑、直达路由、空态、快捷键接线。
 *
 * 数据只用 fixture 里的 `settings.snapshot`（groups + entries + values），
 * 与运行时一致：搜索结果全部来自本地过滤，没有任何协议请求参与。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-search-check.mjs");

const ENTRY = `
export { breadcrumbText, buildSettingsSearchRows, searchSettings, settingsPageTitle } from "./src/host/settingsQuery.ts";
export { SettingsSearch } from "./src/host/SettingsSearch.tsx";
export { SettingsValuePage } from "./src/host/SettingsValuePage.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-search-entry.tsx", loader: "tsx" },
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

const entry = (id, title, extra = {}) => ({
  id,
  titleKey: `key_${id}`,
  title,
  groupKey: "group",
  iconKey: "chat",
  kind: "link",
  ...extra,
});

const SNAPSHOT = {
  groups: [
    {
      key: "settings_group_appearance",
      groupTitle: "外观分类",
      entries: [
        entry("appearance", "外观", { desc: "主题与字号" }),
        entry("miniphone", "小手机", { enabled: false }),
      ],
    },
    {
      key: "settings_group_chat",
      groupTitle: "聊天分类",
      entries: [
        entry("chat", "聊天", { desc: "消息与生成" }),
        entry("pii", "隐私保护", { kind: "switch", toggleKey: "piiGuardEnabled", checked: true }),
        entry("weird", "未知条目", { kind: "mystery" }),
      ],
    },
  ],
  toggles: [{ key: "piiGuardEnabled", enabled: true }],
  display: { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: [],
  values: [
    { key: "appearance.themeMode", type: "enum", value: "dark", options: [{ value: "dark", label: "PROTO-深色" }] },
    { key: "chat.showToolCalls", type: "boolean", value: true },
    { key: "unmapped.thing", type: "text", value: "x" },
  ],
};

const results = (query) => mod.searchSettings(SNAPSHOT, query);
const titles = (query) => results(query).map((hit) => hit.title);

try {
  // ── 1. 索引：只放可进入的条目与可归属的值 ────────────────────────
  const rows = mod.buildSettingsSearchRows(SNAPSHOT);
  assert(rows.length === 4, `索引 4 行（2 个可进入条目 + 2 个可归属值）：实际 ${rows.length}`);
  assert(!rows.some((row) => row.title === "小手机"), "enabled=false 的条目不进索引");
  assert(!rows.some((row) => row.title === "隐私保护"), "开关条目不进索引（它就在分类页上，没有去处）");
  assert(!rows.some((row) => row.title === "未知条目"), "未知 kind 的条目不进索引");
  assert(!rows.some((row) => row.title.includes("Unmapped")), "归不到子页的值不进索引");

  // ── 2. 过滤：条目名 / 分类名 / 值标题都能命中 ───────────────────
  assert(titles("外观").includes("外观"), "按条目名命中");
  assert(titles("聊天分类").includes("聊天"), "按分类名命中");
  assert(titles("theme").includes("Theme Mode"), "按值行标题命中（键派生的词）");
  assert(titles("THEME").includes("Theme Mode"), "大小写不敏感");
  assert(titles("  theme  ").includes("Theme Mode"), "关键词两端空白被忽略");
  assert(titles("外观").includes("Theme Mode"), "值也能通过「所属子页标题」命中");
  assert(results("隐私").length === 0, "只有开关条目匹配时结果为空（开关不在索引里）");
  assert(results("不存在的词").length === 0, "无匹配返回空数组");
  assert(results("").length === 0, "空关键词不返回任何行（不刷 48 条）");

  // ── 3. 排序：标题命中优先于只命中面包屑的 ──────────────────────
  const appearanceHits = results("外观");
  assert(appearanceHits[0]?.title === "外观", `标题命中排最前：${appearanceHits.map((hit) => hit.title).join(",")}`);
  assert(appearanceHits.length === 2, "「外观」同时命中条目与它下面的值行");

  // ── 4. 面包屑：分类 › 条目 ─────────────────────────────────────
  const entryHit = results("外观").find((hit) => hit.title === "外观");
  assert(
    entryHit !== undefined && mod.breadcrumbText(entryHit) === "外观分类 › 外观",
    `条目面包屑：${entryHit === undefined ? "" : mod.breadcrumbText(entryHit)}`,
  );
  const valueHit = results("theme").find((hit) => hit.title === "Theme Mode");
  assert(
    valueHit !== undefined && mod.breadcrumbText(valueHit) === "外观 › Theme Mode",
    `值行面包屑：${valueHit === undefined ? "" : mod.breadcrumbText(valueHit)}`,
  );

  // ── 5. 直达目标 ───────────────────────────────────────────────
  assert(
    entryHit?.target.kind === "entry" && entryHit.target.entryId === "appearance",
    "条目结果指向对应子页的条目 id",
  );
  assert(
    valueHit?.target.kind === "value" &&
      valueHit.target.valueKey === "appearance.themeMode" &&
      valueHit.target.pageId === "appearance",
    "值结果带上所属子页与要高亮的 key",
  );
  assert(mod.settingsPageTitle(SNAPSHOT, { id: "chat", entryIds: ["chat"], keyTokens: [] }) === "聊天", "子页标题取自目录条目");

  // ── 6. 渲染：结果行 / 空态 / 提示 ─────────────────────────────
  const searchView = (query) =>
    render(
      mod.createElement(mod.SettingsSearch, {
        snapshot: SNAPSHOT,
        query,
        onQueryChange: () => undefined,
        onPick: () => undefined,
        onClose: () => undefined,
      }),
    );
  const hitHtml = searchView("theme");
  assert(hitHtml.includes("Theme Mode") && hitHtml.includes("外观 › Theme Mode"), "结果行同时给出标题与面包屑");
  assert(hitHtml.includes('role="listbox"') && hitHtml.includes('role="option"'), "结果列表有 listbox/option 语义");
  assert(hitHtml.includes('aria-label="退出搜索"'), "搜索态有关闭入口");
  const idle = searchView("");
  assert(idle.includes("输入关键词") && !idle.includes('role="option"'), "空关键词时给提示，不列结果");
  const empty = searchView("不存在的词");
  assert(empty.includes("没有找到匹配") && empty.includes("清空搜索"), "无匹配时给提示与「清空搜索」");
  assert(empty.includes('role="status"'), "空态有 status 语义");
  const withResult = searchView("外观");
  assert((withResult.match(/role="option"/g) ?? []).length === 2, "渲染出的结果条数与过滤结果一致");

  // ── 7. 接线：快捷键与高亮 ─────────────────────────────────────
  const hostSettings = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(hostSettings.includes('event.key === "/"') && hostSettings.includes("metaKey"), "支持 / 与 Cmd/Ctrl+K 打开搜索");
  assert(hostSettings.includes('event.key.toLowerCase() === "k"'), "Cmd/Ctrl+K 的判断在");
  assert(hostSettings.includes("event.preventDefault()"), "快捷键会阻止默认行为");
  assert(hostSettings.includes('aria-label={t("settings.search")}'), "分类页顶部有搜索入口");
  assert(hostSettings.includes("host-settings-search"), "搜索入口是吸顶容器");
  assert(hostSettings.includes("<SettingsSearch"), "全屏搜索态挂在这里");
  assert(hostSettings.includes("focusKey: target.valueKey"), "值结果直达时把 focusKey 传给子页");

  const searchSource = readFileSync(join(ROOT, "src", "host", "SettingsSearch.tsx"), "utf8");
  assert(/event\.key (===|!==) "Escape"/.test(searchSource), "搜索态 Esc 关闭");
  assert(searchSource.includes('event.key === "ArrowDown"') && searchSource.includes('event.key === "ArrowUp"'), "上下键移动选中");
  assert(searchSource.includes('event.key !== "Enter"') && searchSource.includes("onPick(hit)"), "Enter 进入选中项");

  const valuePageHtml = render(
    mod.createElement(mod.SettingsValuePage, {
      page: { id: "chat", entryIds: ["chat"], keyTokens: [] },
      title: "聊天",
      snapshot: SNAPSHOT,
      loading: false,
      error: undefined,
      capabilities: { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true, messageWrite: true, multiSelect: true },
      focusKey: "chat.showToolCalls",
      onBack: () => undefined,
      onRetry: () => undefined,
      onWrite: () => undefined,
    }),
  );
  assert(valuePageHtml.includes("host-setting-highlight"), "直达时高亮目标行");
  assert((valuePageHtml.match(/host-setting-highlight/g) ?? []).length === 1, "只高亮一行");
  assert(/host-setting-highlight[\s\S]{0,400}Show Tool Calls/.test(valuePageHtml), "高亮的是结果指向的那一行");

  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-settings-search", "host-search-trigger", "host-search-result", "host-search-empty", "host-setting-highlight"]) {
    assert(css.includes(`.${selector}`), `样式里定义了 .${selector}`);
  }
  assert(/\.host-settings-search\s*\{[^}]*position:\s*sticky/.test(css), "搜索入口是吸顶的");

  if (failures > 0) throw new Error(`设置搜索断言未通过（${failures} 条）`);
  console.log("\n设置搜索断言全部通过（过滤 / 面包屑 / 直达 / 空态 / 快捷键）。");
} finally {
  await rm(TEMP, { force: true });
}
