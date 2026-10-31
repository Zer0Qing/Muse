/**
 * 助手与记忆页的断言：列表/详情渲染、只读参数、记忆分页与搜索空态、能力位缺失提示。
 *
 * 组件用 renderToStaticMarkup 直接渲染（详情视图单独导出，不依赖点击），
 * 交互路径（点行、滚到底、回车搜索）按源码接线断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".assistant-memory-check.mjs");

const ENTRY = `
export { AssistantsPage, AssistantDetailView } from "./src/host/AssistantsPage.tsx";
export { MemoryPage } from "./src/host/MemoryPage.tsx";
export { ASSISTANT_DETAIL_FLAGS, MEMORY_FLAGS, isUnsupportedCommandError, supportsFlag } from "./src/host/featureCapabilities.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "assistant-memory-entry.tsx", loader: "tsx" },
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

const HOST_SETTINGS = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
const HOST_CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const ASSISTANTS = [
  { id: "a1", name: "PROTO-Muse", isDefault: true, avatarUri: "/api/media/face-1" },
  { id: "a2", name: "PROTO-Iris" },
];
const PROVIDERS = [{ id: "p1", name: "OpenAI", enabled: true, models: [{ id: "gpt-x", name: "PROTO-GPT-X" }] }];
const DETAIL = {
  id: "a1",
  name: "PROTO-Muse",
  description: "PROTO-简介",
  modelId: "gpt-x",
  providerId: "p1",
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 4096,
  personaSummary: "PROTO-人设摘要",
  systemPromptLength: 1234,
  toolCount: 12,
  contextMessageSize: 40,
  reasoningLevel: "medium",
  enabled: true,
};

try {
  // ── 1. 能力协商 ─────────────────────────────────────────────
  assert(mod.supportsFlag(undefined, mod.MEMORY_FLAGS) === true, "老 Host（没能力位）先试一次");
  assert(mod.supportsFlag([], mod.MEMORY_FLAGS) === false, "下发了能力位但不含 memory → 不支持");
  assert(mod.supportsFlag(["memory"], mod.MEMORY_FLAGS) === true, "声明的能力位能识别");
  assert(mod.isUnsupportedCommandError("unsupported host command: memory.list") === true, "运行时拒绝也认");
  assert(mod.isUnsupportedCommandError("focus_not_held") === false, "业务错误不算「不支持」");

  // ── 2. 助手列表 ─────────────────────────────────────────────
  const listHtml = render(
    mod.createElement(mod.AssistantsPage, {
      assistants: ASSISTANTS,
      providers: PROVIDERS,
      details: { a1: DETAIL },
      flags: ["assistants_detail", "providers"],
      loading: false,
      detailUnsupported: false,
      onRequestDetail: () => undefined,
      onRequestProviders: () => undefined,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(listHtml.includes("PROTO-Muse") && listHtml.includes("PROTO-Iris"), "列表显示所有助手名");
  assert(listHtml.includes(">默认<"), "默认助手有标记");
  assert(listHtml.includes("PROTO-GPT-X"), "详情 + providers 能翻出模型名");
  assert((listHtml.match(/muse-listrow is-interactive/g) ?? []).length === 2, "每行可点进详情");
  assert(listHtml.includes('src="/api/media/face-1"'), "头像用 /api/media 地址渲染");

  const emptyList = render(
    mod.createElement(mod.AssistantsPage, {
      assistants: [],
      providers: [],
      details: {},
      flags: ["assistants_detail"],
      loading: false,
      detailUnsupported: false,
      onRequestDetail: () => undefined,
      onRequestProviders: () => undefined,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(emptyList.includes("手机上还没有助手"), "没有助手时给空态");

  // ── 3. 助手详情 ─────────────────────────────────────────────
  const detailHtml = render(
    mod.createElement(mod.AssistantDetailView, {
      assistant: ASSISTANTS[0],
      detail: DETAIL,
      modelName: "PROTO-GPT-X",
      supported: true,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(detailHtml.includes("PROTO-简介"), "详情显示简介");
  assert(detailHtml.includes("PROTO-人设摘要") && detailHtml.includes("host-persona"), "人设摘要可折叠（6 行截断）");
  assert(detailHtml.includes("展开"), "人设摘要给展开入口");
  assert(detailHtml.includes("温度") && detailHtml.includes("0.7"), "温度只读展示");
  assert(detailHtml.includes("4,096"), "最大 Token 走 Intl 分组");
  assert(detailHtml.includes("已配置 1,234 字"), "提示词只显示字数，不给正文");
  assert(!detailHtml.includes("系统提示词正文"), "提示词正文没有出现在页面上");
  assert(detailHtml.includes("12 个"), "工具数展示");
  assert(/role="switch"/.test(detailHtml) && detailHtml.includes("disabled"), "启用开关是只读的");
  assert(!/<input/i.test(detailHtml), "只读参数不出现输入框");
  assert(detailHtml.includes("用这个助手开始会话"), "顶部能直接发起会话");

  const unsupportedDetail = render(
    mod.createElement(mod.AssistantDetailView, {
      assistant: ASSISTANTS[0],
      detail: undefined,
      modelName: undefined,
      supported: false,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(unsupportedDetail.includes("当前版本暂不支持在网页端查看助手详情"), "缺能力位时给提示而不是空白");

  // ── 4. 记忆页 ───────────────────────────────────────────────
  const memoryPage = (overrides) =>
    render(
      mod.createElement(mod.MemoryPage, {
        memory: {
          items: [],
          hasMore: false,
          loading: false,
          error: undefined,
          query: undefined,
          unsupported: false,
          ...overrides,
        },
        supported: true,
        onRequest: () => undefined,
        onBack: () => undefined,
      }),
    );

  const items = [
    { id: "m1", content: "PROTO-记忆内容", createdAt: Date.now(), source: "conversation", pinned: true },
    { id: "m2", content: "PROTO-第二条", createdAt: Date.now() - 3 * 24 * 60 * 60 * 1000, source: "manual" },
  ];
  const listMemory = memoryPage({ items, hasMore: true });
  assert(listMemory.includes("PROTO-记忆内容") && listMemory.includes("PROTO-第二条"), "记忆内容都列出来");
  assert(listMemory.includes("置顶"), "置顶标记");
  assert(listMemory.includes("conversation"), "来源标签");
  assert(listMemory.includes("刚刚") && listMemory.includes("3 天前"), "时间走本地化相对时间");
  assert(listMemory.includes("加载更多"), "还有更多时给加载入口");
  const lastMemory = memoryPage({ items, hasMore: false });
  assert(lastMemory.includes("没有更多了") && !lastMemory.includes("加载更多"), "到底后显示「没有更多了」");
  const emptyMemory = memoryPage({});
  assert(emptyMemory.includes("还没有记忆"), "无记忆时给引导");
  const noHit = memoryPage({ items: [], query: "PROTO-关键词" });
  assert(noHit.includes("PROTO-关键词") && noHit.includes("清空"), "搜索无命中时显示关键词与「清空」");
  assert(memoryPage({ unsupported: true }).includes("当前版本暂不支持在网页端查看记忆"), "不支持时给提示");
  assert(memoryPage({ error: "PROTO-错误" }).includes("PROTO-错误"), "读取失败显示错误与重试");

  // ── 5. 接线 ───────────────────────────────────────────────
  assert(HOST_SETTINGS.includes('openPage.entry.id === "assistants"') && HOST_SETTINGS.includes("<AssistantsPage"), "设置里「助手」条目进助手页");
  assert(HOST_SETTINGS.includes('openPage.entry.id === "memory"') && HOST_SETTINGS.includes("<MemoryPage"), "「记忆」条目进记忆页");
  assert(HOST_CHAT.includes("onStartSession={startSessionWith}") && HOST_CHAT.includes('type: "session.new", assistantId'), "详情页能带着 assistantId 开会话");
  assert(CONNECTION.includes('type: "assistants.detail", assistantId'), "助手详情按 assistantId 请求");
  assert(CONNECTION.includes('type: "memory.list", limit: MEMORY_PAGE_SIZE') && CONNECTION.includes("cursor: options.cursor"), "记忆列表带 limit 与 cursor 分页");
  assert(CONNECTION.includes('type: "memory.search", query, limit: MEMORY_PAGE_SIZE'), "记忆搜索带 query");
  assert(CONNECTION.includes("MEMORY_PAGE_SIZE = 30"), "每页 30 条（Host 默认值）");
  assert(/host-assistant-actions|host-memory-list|host-persona/.test(CSS), "新增样式类存在");

  if (failures > 0) throw new Error(`助手与记忆断言未通过（${failures} 条）`);
  console.log("\n助手与记忆断言全部通过（列表 / 详情 / 分页 / 搜索 / 能力位）。");
} finally {
  await rm(TEMP, { force: true });
}
