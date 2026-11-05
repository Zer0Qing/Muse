/**
 * 「运行」页断言：三 Tab 切换、日志尾部顺序与空态、诊断 JSON 复制与解析失败、
 * 统计的样本不足标注，以及跨语言契约（五语同键 + 三条命令都发得出去）。
 *
 * 组件用 renderToStaticMarkup 直接渲染；交互路径（切 Tab、点刷新）按源码断言，
 * 与 check-data-platform.mjs 同一套做法。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".runtime-page-check.mjs");

const ENTRY = `
export { HostRuntimePage } from "./src/host/HostRuntimePage.tsx";
export { RUNTIME_FLAGS, supportsFlag } from "./src/host/featureCapabilities.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "runtime-entry.tsx", loader: "tsx" },
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
const API = readFileSync(join(ROOT, "src", "api.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");

const STATS = {
  webSamples: 64,
  webP95Ms: 210,
  webP95Reliable: true,
  jankCount: 11,
  slowCount: 4,
  blockCount: 1,
  worstMs: 1500,
  windowSeconds: 120,
};

const DIAGNOSTICS_JSON = JSON.stringify({ app: { versionName: "2.7.0", versionCode: 286 }, jank: { jankCount: 3 } });

const runtimeState = (overrides = {}) => ({
  logs: ["I/Chat: 第一条", "W/Db: 第二条"],
  logsLoading: false,
  diagnostics: DIAGNOSTICS_JSON,
  diagnosticsLoading: false,
  stats: STATS,
  statsLoading: false,
  error: undefined,
  unsupported: false,
  ...overrides,
});

const page = (overrides = {}, props = {}) =>
  render(
    mod.createElement(mod.HostRuntimePage, {
      title: "PROTO-运行",
      runtime: runtimeState(overrides.runtime ?? {}),
      supported: overrides.supported ?? true,
      onRequestLogs: () => undefined,
      onRequestDiagnostics: () => undefined,
      onRequestStats: () => undefined,
      onBack: () => undefined,
      ...props,
    }),
  );

try {
  // ── 1. 三个 Tab 都在，默认进日志 ───────────────────────────
  const html = page();
  assert(html.includes("PROTO-运行"), "页面标题来自 Host 目录条目");
  assert(/role="tablist"/.test(html), "三个 Tab 是可访问的 tablist");
  assert((html.match(/role="tab"/g) ?? []).length === 3, "恰好三个 Tab（不新增顶级页面）");
  assert(html.includes(">日志<") && html.includes(">诊断<") && html.includes(">统计<"), "三个 Tab 的名字都在");
  assert((html.match(/aria-selected="true"/g) ?? []).length === 1, "只有一个 Tab 是选中态");
  assert(html.includes("host-runtime-tab is-selected") && html.includes(">日志<"), "默认选中「日志」");

  // ── 2. 日志：最新在上 + 空态 ───────────────────────────────
  assert(html.includes("最新在上"), "日志标明排序方向（不然没人知道先看哪一行）");
  assert(html.indexOf("W/Db: 第二条") < html.indexOf("I/Chat: 第一条"), "日志按最新在上渲染");
  assert(html.includes("host-runtime-log-line"), "每行是独立节点，不是整块 innerText");
  const emptyLogs = page({ runtime: { logs: [] } });
  assert(emptyLogs.includes("没有内容"), "空日志给「没有内容」而不是空白块");
  const noLogsYet = page({ runtime: { logs: undefined } });
  assert(noLogsYet.includes("没有内容"), "还没发命令时不假装有一堆空行");

  // ── 3. 诊断：美化输出、复制与解析失败 ──────────────────────
  // 静态渲染只能停在默认 Tab，Tab 内的分支按源码断言（与切页行为一起看）。
  const RUNTIME_PAGE = readFileSync(join(ROOT, "src", "host", "HostRuntimePage.tsx"), "utf8");
  assert(RUNTIME_PAGE.includes('t("runtime.copyJson")') && RUNTIME_PAGE.includes("copyTextToClipboard"), "诊断 Tab 提供复制走既有剪贴板兜底");
  assert(RUNTIME_PAGE.includes("JSON.stringify(JSON.parse(") && RUNTIME_PAGE.includes('t("runtime.jsonInvalid")'), "诊断 JSON 先解析再美化，解析失败说出来而不是空白一片");
  assert(RUNTIME_PAGE.includes("prettyDiagnostics === undefined") && RUNTIME_PAGE.includes('t("runtime.noDiagnostics")'), "没有快照时给明确说明");

  // ── 4. 统计：p95 可信性与三档计数 ──────────────────────────
  assert(RUNTIME_PAGE.includes('t("runtime.p95FewSamples")') && RUNTIME_PAGE.includes("webP95Reliable"), "样本不足时标注 p95 仅供参考");
  assert(RUNTIME_PAGE.includes("stats.webSamples") && RUNTIME_PAGE.includes("stats.windowSeconds"), "样本数与窗口时长都显示");
  assert(RUNTIME_PAGE.includes("runtime.jank") && RUNTIME_PAGE.includes("stats.slowCount") && RUNTIME_PAGE.includes("stats.blockCount"), "三档计数一行给全");

  // ── 5. 未支持 / 错误 ───────────────────────────────────────
  const unsupported = page({ supported: false });
  assert(unsupported.includes("当前版本暂不支持在网页端查看运行信息"), "缺能力位时给说明而不是空页");
  const failed = page({ runtime: { error: "PROTO-错误" } });
  assert(failed.includes("PROTO-错误") && failed.includes(">重试<"), "读取失败给重试入口");

  // ── 6. 五语同键（check-i18n 锁全集，这里锁本组的键确实都在） ──
  const KEYS = [
    "runtime.tab.logs",
    "runtime.tab.diagnostics",
    "runtime.tab.stats",
    "runtime.loading",
    "runtime.refresh",
    "runtime.empty",
    "runtime.unsupported",
    "runtime.newestFirst",
    "runtime.copyJson",
    "runtime.copied",
    "runtime.jsonInvalid",
    "runtime.noDiagnostics",
    "runtime.samples",
    "runtime.p95",
    "runtime.p95FewSamples",
    "runtime.jank",
    "runtime.worst",
    "runtime.window",
  ];
  const dictHasAll = (dict) => KEYS.every((key) => typeof dict[key] === "string" && dict[key].length > 0);
  for (const name of ["ZH", "EN", "JA", "KO", "RU"]) {
    assert(dictHasAll(mod[name]), `${name} 的 runtime.* 词条齐全`);
  }

  // ── 7. 接线：条目、命令、能力位、装配 ───────────────────────
  assert(HOST_SETTINGS.includes('openPage.entry.id === "diagnostics"') && HOST_SETTINGS.includes("<HostRuntimePage"), "「运行诊断」条目进运行页");
  assert(HOST_SETTINGS.includes("RUNTIME_FLAGS") && HOST_SETTINGS.includes("supportsFlag(capabilityFlags, RUNTIME_FLAGS)"), "进页前先过能力位");
  assert(mod.supportsFlag(undefined, mod.RUNTIME_FLAGS) === true, "老 Host 没下发能力位时先试一次");
  assert(mod.supportsFlag(["runtime_logs"], mod.RUNTIME_FLAGS) === true, "任一条能力位命中即算支持");
  assert(mod.supportsFlag([], mod.RUNTIME_FLAGS) === false, "明确的能力位列表里没有就是不支持");

  for (const command of ['type: "runtime.logs"', 'type: "runtime.diagnostics"', 'type: "runtime.stats"']) {
    assert(CONNECTION.includes(command), `客户端发得出 ${command}`);
  }
  for (const key of ["readonly logs?:", "readonly diagnostics?:", "readonly stats?:", '"runtime.logs"', '"runtime.stats"']) {
    assert(API.includes(key), `api.ts 声明了 ${key}`);
  }
  assert(
    CONNECTION.includes('kind: "runtimeLogs"') && CONNECTION.includes('kind: "runtimeDiagnostics"') && CONNECTION.includes('kind: "runtimeStats"'),
    "三条命令都登记进 pendingQueries（超时/失败才有地方结算）",
  );
  assert(
    CONNECTION.includes("logsLoading: false, diagnosticsLoading: false, statsLoading: false"),
    "断线清理把三个 loading 都复位（否则切页永远转圈）",
  );
  assert(
    CHAT.includes("onRequestRuntimeLogs={requestRuntimeLogs}") && CHAT.includes("runtime={runtime}"),
    "HostChat 把运行切片接到设置页",
  );
  assert(/host-runtime-tabs/.test(CSS) && /host-runtime-logs/.test(CSS), "新增样式类存在");

  if (failures > 0) throw new Error(`运行页断言未通过（${failures} 条）`);
  console.log("\n运行页断言全部通过（三 Tab / 日志顺序 / 诊断 JSON / 统计样本 / 五语词条 / 跨语言接线）。");
} finally {
  await rm(TEMP, { force: true });
}
