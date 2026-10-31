/**
 * 设置主页的渲染断言：界面文本**只**读协议字段。
 *
 * `settings.snapshot` 现在直接下发已本地化的 `title` / `desc` / `groupTitle`，
 * Web 端不再保存任何文案表（`host/settings-copy.ts` 已删除）。这个脚本把真实的
 * `HostSettings` 组件渲染成 HTML，用一眼就能认出来的假文案（PROTO-…）验证：
 *  · 分组标题 / 条目标题 / 说明都来自协议字段，不是资源名，也不是本地表；
 *  · `titleKey` / `key` 只作标识，不会漏到界面上；
 *  · kind → 控件（link/action 右箭头、switch 开关）、iconKey 图标、加载/失败态都还在。
 *
 * 用法：npm --workspace @muse/client run test
 * 说明：靠 vite 自带的 esbuild 把组件打成临时 ESM 再 import，不新增依赖；
 *      临时文件在 finally 里删掉。
 */
import { build } from "esbuild";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-render-check.mjs");

const ENTRY = `
export { HostSettings } from "./src/host/HostSettings.tsx";
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

/** 只存在于协议字段的文案：任何本地表 / 资源名都没法造出这些串。 */
const groupTitle = "PROTO-GROUP-模型与供应商";
const linkTitle = "PROTO-ENTRY-供应商";
const linkDesc = "PROTO-DESC-管理模型供应商";
const switchTitle = "PROTO-ENTRY-PII";
const bareTitle = "PROTO-ENTRY-网络代理";

const snapshot = {
  groups: [
    {
      key: "settings_screen_group_models",
      groupTitle,
      entries: [
        {
          id: "provider",
          titleKey: "settings_screen_provider",
          descKey: "settings_screen_provider_desc",
          title: linkTitle,
          desc: linkDesc,
          groupKey: "settings_screen_group_models",
          iconKey: "provider",
          kind: "link",
          enabled: true,
        },
      ],
    },
    {
      key: "settings_screen_group_privacy",
      groupTitle: "PROTO-GROUP-隐私",
      entries: [
        {
          id: "pii_guard",
          titleKey: "settings_screen_pii_guard",
          descKey: "settings_screen_pii_guard_desc",
          title: switchTitle,
          desc: "PROTO-DESC-PII",
          groupKey: "settings_screen_group_privacy",
          iconKey: "shield",
          kind: "switch",
          toggleKey: "piiGuardEnabled",
          checked: true,
        },
        {
          id: "proxy",
          titleKey: "proxy_title",
          title: bareTitle,
          groupKey: "settings_screen_group_privacy",
          iconKey: "proxy",
          kind: "action",
        },
      ],
    },
  ],
  toggles: [{ key: "piiGuardEnabled", enabled: true }],
  display: { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: ["piiGuardEnabled"],
};

async function render() {
  await build({
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-render-entry.tsx", loader: "tsx" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    loader: { ".css": "empty" },
    // react / react-dom 留在外部，由 Node 从工作区 node_modules 解析。
    external: ["react", "react-dom", "react-dom/server"],
    outfile: TEMP,
    logLevel: "warning",
  });
  const module = await import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
  return (settings) =>
    module.renderToStaticMarkup(
      module.createElement(module.HostSettings, {
        settings,
        capabilities: { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true },
        onBack: () => undefined,
        onRetry: () => undefined,
        onToggle: () => undefined,
        onWrite: () => undefined,
      assistants: [],
      providers: [],
      assistantDetails: {},
      assistantDetailUnsupported: false,
      memory: { items: [], hasMore: false, loading: false, error: undefined, query: undefined, unsupported: false },
      capabilityFlags: [],
      onRequestAssistantDetail: () => undefined,
      onRequestProviders: () => undefined,
      onRequestMemory: () => undefined,
      onStartSession: () => undefined,
      knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
      knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      onRequestKnowledge: () => undefined,
      onRequestKnowledgeDocuments: () => undefined,
      onRequestTasks: () => undefined,
        assistants: [],
        providers: [],
        assistantDetails: {},
        assistantDetailUnsupported: false,
        memory: { items: [], hasMore: false, loading: false, error: undefined, query: undefined, unsupported: false },
        capabilityFlags: [],
        onRequestAssistantDetail: () => undefined,
        onRequestProviders: () => undefined,
        onRequestMemory: () => undefined,
        onStartSession: () => undefined,
      knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
      knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      onRequestKnowledge: () => undefined,
      onRequestKnowledgeDocuments: () => undefined,
      onRequestTasks: () => undefined,
        knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
        knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
        tasks: { items: [], loading: false, error: undefined, unsupported: false },
        onRequestKnowledge: () => undefined,
        onRequestKnowledgeDocuments: () => undefined,
        onRequestTasks: () => undefined,
      }),
    );
}

const renderSettings = await render();
try {
  const html = renderSettings({ snapshot, loading: false });

  assert(html.includes(groupTitle), "分组标题来自协议 groupTitle");
  assert(html.includes(linkTitle) && html.includes(linkDesc), "条目标题/说明来自协议 title/desc");
  assert(html.includes(switchTitle) && html.includes(bareTitle), "每条条目的标题都来自协议字段");
  assert(!html.includes("settings_screen_provider"), "资源名不会漏进界面");
  assert(!html.includes("settings_screen_group_models"), "分组资源名不会漏进界面");
  assert(!html.includes("settings_screen_pii_guard"), "开关条目的资源名不会漏进界面");

  assert((html.match(/role="switch"/g) ?? []).length === 1, "kind=switch 渲染成开关");
  assert(html.includes('aria-checked="true"'), "开关初值取 checked");
  assert((html.match(/muse-listrow-chevron/g) ?? []).length === 2, "link/action 渲染右箭头");
  assert((html.match(/class="muse-settings-icon"/g) ?? []).length === 3, "每行左侧都有 iconKey 图标");
  assert(html.includes('class="muse-listrow-title">' + bareTitle), "无说明的条目只渲染标题");
  assert(!html.includes(">undefined<"), "可选文案缺失时不会渲染出 undefined");
  assert(html.includes("muse-section-label"), "分组标题用 SectionLabel");

  const loading = renderSettings({ loading: true });
  // 骨架延迟 300ms：刚进页时只给一个可被读屏播报的空白占位，不闪骨架
  assert(
    loading.includes('role="status"') && !loading.includes("muse-skeleton"),
    "加载中先给安静的占位（骨架延迟 300ms 才出）",
  );

  const failed = renderSettings({ loading: false, error: "PROTO-ERROR-读取失败" });
  assert(failed.includes("PROTO-ERROR-读取失败") && failed.includes(">重试<"), "失败显示可重试错误条");

  if (failures > 0) throw new Error(`设置渲染断言未通过（${failures} 条）`);
  console.log("\n设置渲染断言全部通过（界面文本只读协议字段）。");
} finally {
  await rm(TEMP, { force: true });
}
