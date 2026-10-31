/**
 * 知识库页与定时任务页的断言：两页渲染、文档分页、任务字段格式化、空态、能力位。
 *
 * 组件用 renderToStaticMarkup 直接渲染；交互（点知识库、滚到底）与请求接线按源码断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".knowledge-tasks-check.mjs");

const ENTRY = `
export { KnowledgePage, KnowledgeDocumentsView } from "./src/host/KnowledgePage.tsx";
export { TasksPage } from "./src/host/TasksPage.tsx";
export { KNOWLEDGE_FLAGS, TASK_FLAGS, supportsFlag } from "./src/host/featureCapabilities.ts";
export { formatRelativeTime, formatBytes } from "./src/i18n/format.ts";
export { ZH, EN } from "./src/i18n/messages.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "knowledge-tasks-entry.tsx", loader: "tsx" },
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
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const KNOWLEDGE = {
  bases: [
    { id: "kb1", name: "PROTO-知识库", documentCount: 12, chunkCount: 345, createdAt: 1, updatedAt: 2, embeddingBackend: "PROTO-bge-m3" },
    { id: "kb2", name: "PROTO-无向量库", documentCount: 0, chunkCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  loading: false,
  error: undefined,
  unsupported: false,
};

const DOCUMENTS = (items, hasMore) => ({ kbId: "kb1", items, hasMore, loading: false, error: undefined, unsupported: false });

const knowledgePage = (overrides = {}) =>
  render(
    mod.createElement(mod.KnowledgePage, {
      knowledge: KNOWLEDGE,
      documents: DOCUMENTS([], false),
      supported: true,
      onRequestBases: () => undefined,
      onRequestDocuments: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

const tasksPage = (overrides = {}) =>
  render(
    mod.createElement(mod.TasksPage, {
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      supported: true,
      onRequest: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

try {
  // ── 1. 知识库列表 ───────────────────────────────────────────
  const listHtml = knowledgePage();
  assert(listHtml.includes("PROTO-知识库") && listHtml.includes("PROTO-无向量库"), "列出所有知识库");
  assert(listHtml.includes("12 个文档 · 345 个分块"), "文档数与分块数按本地化格式展示");
  assert(listHtml.includes("PROTO-bge-m3"), "显示 embedding 后端");
  assert(listHtml.includes("未配置"), "没有向量后端时给占位");
  assert((listHtml.match(/muse-listrow is-interactive/g) ?? []).length === 2, "每个知识库都可以点进文档列表");
  assert(listHtml.includes("在手机上导入与管理知识库"), "明确写清只读：在手机上导入与管理");

  const emptyKnowledge = knowledgePage({ knowledge: { ...KNOWLEDGE, bases: [] } });
  assert(emptyKnowledge.includes("还没有知识库"), "没有知识库时给空态");
  const unsupportedKnowledge = knowledgePage({ supported: false });
  assert(unsupportedKnowledge.includes("当前版本暂不支持在网页端查看知识库"), "缺能力位时给提示");
  const failedKnowledge = knowledgePage({ knowledge: { ...KNOWLEDGE, error: "PROTO-错误" } });
  assert(failedKnowledge.includes("PROTO-错误") && failedKnowledge.includes(">重试<"), "读取失败显示错误与重试");

  // ── 2. 文档列表与分页 ───────────────────────────────────────
  const doc = (id, title, chunks, size, status) => ({ id, title, chunkCount: chunks, sizeBytes: size, createdAt: Date.now(), status });
  const docsView = (items, hasMore, overrides = {}) =>
    render(
      mod.createElement(mod.KnowledgeDocumentsView, {
        base: KNOWLEDGE.bases[0],
        documents: { kbId: "kb1", items, hasMore, loading: false, error: undefined, unsupported: false },
        supported: true,
        onRequestDocuments: () => undefined,
        onBack: () => undefined,
        ...overrides,
      }),
    );
  const docsWithMore = docsView([doc("d1", "PROTO-文档一", 12, 2048, "indexed"), doc("d2", "PROTO-文档二", 3, 300)], true);
  assert(docsWithMore.includes("PROTO-文档一") && docsWithMore.includes("PROTO-文档二"), "文档标题列出来");
  assert(docsWithMore.includes("12 个分块"), "文档分块数");
  assert(/KB|kB/.test(docsWithMore), `文档大小走 Intl 单位：${docsWithMore.includes("2 KB") ? "2 KB" : "见页内"}`);
  assert(docsWithMore.includes("indexed"), "文档状态原样展示");
  assert(docsWithMore.includes("加载更多"), "还有下一页时给加载入口");
  const docsEnd = docsView([doc("d1", "PROTO-文档一", 12, 2048, "indexed")], false);
  assert(docsEnd.includes("没有更多了") && !docsEnd.includes("加载更多"), "到底后显示「没有更多了」");
  const docsEmpty = docsView([], false);
  assert(docsEmpty.includes("这个知识库还没有文档"), "知识库无文档时给空态");
  assert(docsView([], false, { supported: false }).includes("当前版本暂不支持在网页端查看知识库"), "文档页缺能力位也给提示");
  assert(mod.formatBytes(300) === "300 byte" || mod.formatBytes(300).includes("300"), `字节格式化：${mod.formatBytes(300)}`);
  assert(/KB|kB/.test(mod.formatBytes(2048)), `KB 格式化：${mod.formatBytes(2048)}`);
  assert(/MB/.test(mod.formatBytes(5 * 1024 * 1024)), `MB 格式化：${mod.formatBytes(5 * 1024 * 1024)}`);
  assert(CONNECTION.includes('type: "knowledge.documents"') && CONNECTION.includes("cursor: options.cursor"), "文档分页带 cursor");
  assert(CONNECTION.includes("KNOWLEDGE_PAGE_SIZE = 20"), "每页 20 条（Host 默认值）");
  assert(/host-memory-more/.test(readFileSync(join(ROOT, "src", "host", "KnowledgePage.tsx"), "utf8")), "还有更多时给加载按钮");

  // ── 3. 定时任务 ─────────────────────────────────────────────
  const now = Date.now();
  const task = {
    id: "t1",
    name: "PROTO-每日简报",
    cron: "0 8 * * *",
    enabled: true,
    nextRunAt: now + 3 * 60 * 60 * 1000 + 5 * 60 * 1000,
    lastRunAt: now - 2 * 60 * 60 * 1000,
    lastResult: "PROTO-上次结果",
  };
  const tasksHtml = tasksPage({ tasks: { items: [task], loading: false, error: undefined, unsupported: false } });
  assert(tasksHtml.includes("PROTO-每日简报"), "任务名");
  assert(tasksHtml.includes("0 8 * * *"), "cron 原样展示");
  assert(tasksHtml.includes("已启用"), "启用状态");
  assert(tasksHtml.includes("3 小时后"), "下次执行用未来相对时间");
  assert(mod.formatRelativeTime(now + 3 * 60 * 60 * 1000 + 5 * 60 * 1000, now, "zh") === "3 小时后", "未来小时取整不跨桶（边界稳定）");
  assert(tasksHtml.includes("2 小时前"), "上次执行用相对时间");
  assert(tasksHtml.includes("PROTO-上次结果"), "上次结果");
  assert(tasksHtml.includes("在手机上管理定时任务"), "明确写清只读：在手机上管理");

  const disabledTask = tasksPage({ tasks: { items: [{ ...task, enabled: false, nextRunAt: undefined, lastRunAt: undefined, lastResult: undefined }], loading: false, error: undefined, unsupported: false } });
  assert(disabledTask.includes("已暂停"), "停用的任务显示「已暂停」");
  assert((disabledTask.match(/还没有执行过/g) ?? []).length >= 2, "没有下次/上次执行时给「还没有执行过」");

  const emptyTasks = tasksPage();
  assert(emptyTasks.includes("还没有定时任务"), "没有任务时给引导");
  const unsupportedTasks = tasksPage({ supported: false });
  assert(unsupportedTasks.includes("当前版本暂不支持在网页端查看定时任务"), "缺能力位时给提示");
  const failedTasks = tasksPage({ tasks: { items: [], loading: false, error: "PROTO-错误", unsupported: false } });
  assert(failedTasks.includes("PROTO-错误") && failedTasks.includes(">重试<"), "任务读取失败可重试");

  // ── 4. 未来相对时间（zh / en）──────────────────────────────
  assert(mod.formatRelativeTime(now - 30 * 1000, now, "zh") === "刚刚", "过去 30 秒仍是刚刚");
  assert(mod.formatRelativeTime(now + 30 * 1000, now, "zh") === "即将", "未来半分钟是「即将」");
  assert(mod.formatRelativeTime(now + 12 * 60 * 1000, now, "zh") === "12 分钟后", "未来分钟");
  assert(mod.formatRelativeTime(now + 3 * 60 * 60 * 1000, now, "en") === "in 3 h", "未来小时（en）");
  assert(mod.formatRelativeTime(now + 2 * 24 * 60 * 60 * 1000, now, "zh") === "2 天后", "未来天数");

  // ── 5. 词条与接线 ─────────────────────────────────────────
  for (const key of ["knowledge.title", "knowledge.readOnly", "knowledge.documents", "knowledge.unsupported", "tasks.title", "tasks.readOnly", "tasks.nextRun", "tasks.unsupported", "common.loadMore", "common.noMore"]) {
    assert(typeof mod.ZH[key] === "string" && typeof mod.EN[key] === "string" && mod.ZH[key].length > 0 && mod.EN[key].length > 0, `词条 ${key} 中英都有`);
  }
  assert(HOST_SETTINGS.includes('openPage.entry.id === "rag"') && HOST_SETTINGS.includes("<KnowledgePage"), "「知识库与检索」条目进知识库页");
  assert(HOST_SETTINGS.includes('openPage.entry.id === "scheduled_tasks"') && HOST_SETTINGS.includes("<TasksPage"), "「定时任务」条目进任务页");
  assert(mod.supportsFlag(undefined, mod.KNOWLEDGE_FLAGS) === true && mod.supportsFlag([], mod.KNOWLEDGE_FLAGS) === false, "知识库能力位判断");
  assert(mod.supportsFlag(["tasks"], mod.TASK_FLAGS) === true && mod.supportsFlag(["knowledge"], mod.TASK_FLAGS) === false, "定时任务能力位判断");
  assert(CONNECTION.includes('type: "knowledge.list"') && CONNECTION.includes('type: "tasks.list"'), "两个读命令都发得出去");
  assert(/host-task-cron|host-task-meta/.test(CSS), "任务卡片样式存在");

  if (failures > 0) throw new Error(`知识库与定时任务断言未通过（${failures} 条）`);
  console.log("\n知识库与定时任务断言全部通过（两页渲染 / 分页 / 任务格式化 / 空态 / 能力位）。");
} finally {
  await rm(TEMP, { force: true });
}
