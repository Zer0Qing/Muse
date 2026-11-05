/**
 * Host 连接的**收尾与结算**断言：断线要把转圈停下、待办要清干净；
 * `command.duplicate` 要结算但不能重放副作用。
 *
 * 这两条都是从真实缺陷来的（不是给现有代码补测试）：
 *  1. 原来的 effect cleanup 只清 5 个 pending 映射，漏了 `pendingQueries`/`pendingMemory`/
 *     `pendingDetails`，而且**没有任何地方把 `loading` 复位** —— 连接一断，
 *     知识库 / 文档 / 任务 / 系统状态 / 记忆 / 设置页就永远停在「加载中」，
 *     用户只能整页刷新才能重新看到数据。
 *  2. `api.ts` 的事件类型里声明了 `command.duplicate`，但处理器只有
 *     `command.accepted` 与 `error` 两条分支 —— 真收到 duplicate 时请求永不结算。
 *
 * 用法：npm --workspace @muse/client run test
 *
 * 说明：这一条按仓库既有约定做**源码结构断言**（同 check-knowledge-tasks.mjs 的
 * 「交互与请求接线按源码断言」）。它证明的是"接线存在且顺序正确"，
 * 不是端到端行为；行为层要等浏览器/真机验收。
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const source = readFileSync(join(ROOT, "src/host/useHostConnection.ts"), "utf8");

const failures = [];
const must = (name, condition, detail = "") => {
  if (!condition) failures.push(`${name}${detail ? ` — ${detail}` : ""}`);
};

// ── 1) 断线清理：八个 pending 映射都要清 ───────────────────────────────
const cleanupStart = source.indexOf("return () => {");
const cleanup = cleanupStart >= 0 ? source.slice(cleanupStart, source.indexOf("}, [retryToken]")) : "";
must("找到 retryToken 的清理块", cleanup.length > 0);

const PENDING_MAPS = [
  "pendingPreviews",
  "pendingSettings",
  "pendingWrites",
  "pendingRollbacks",
  "pendingAccepts",
  "pendingQueries",
  "pendingMemory",
  "pendingDetails",
];
for (const name of PENDING_MAPS) {
  must(`断线要清 ${name}`, cleanup.includes(`${name}.current.clear()`));
}

// ── 2) 断线清理：在转圈的只读切片都要停下 ─────────────────────────────
const LOADING_SLICES = ["setMemory", "setKnowledge", "setKnowledgeDocuments", "setTasks", "setSystem", "setSettings"];
for (const setter of LOADING_SLICES) {
  const resets = [...cleanup.matchAll(new RegExp(`${setter}\\(\\(current\\) => \\(\\{ \\.\\.\\.current, loading: false \\}\\)\\)`, "g"))];
  must(`${setter} 断线时必须复位 loading`, resets.length >= 1);
}

// ── 3) duplicate：必须结算，且不得重放 accepted 副作用 ────────────────
const dupStart = source.indexOf('if (event.type === "command.duplicate")');
must("必须处理 command.duplicate（类型里已声明）", dupStart >= 0);
if (dupStart >= 0) {
  const dupBlock = source.slice(dupStart, source.indexOf('if (event.type === "error")', dupStart));
  must("duplicate 块要按 requestId 结算待办", dupBlock.includes("pendingAccepts.current.delete(requestId)"));
  must("duplicate 块要清只读查询的待办", dupBlock.includes("pendingQueries.current.delete(requestId)"));
  must("duplicate 不得重放 accepted 回调（会把乐观写二次应用）", !/accepted\(/.test(dupBlock));
  must("duplicate 不得触发回滚（命令其实已执行成功）", !/onReject|Rollback\b.*\(requestId\)\?\./.test(dupBlock));
}

// ── 4) accepted 仍在 duplicate 之前：两条分支不能互相吞掉 ─────────────
const acceptedAt = source.indexOf('if (event.type === "command.accepted")');
must("accepted 分支必须存在", acceptedAt >= 0);
must("accepted 在 duplicate 之前处理", acceptedAt >= 0 && dupStart >= 0 && acceptedAt < dupStart);

// ── 5) pending 超时：接线必须覆盖每一个登记点 ───────────────────────
// 逐个登记点检查：同一处可能连着登记两个映射（回滚 + accepted），
// 上表一次即可覆盖，所以规则是「登记行之后的三行内出现 arm」，而不是数量相等。
const lines = source.split("\n");
const unArmed = [];
for (let index = 0; index < lines.length; index += 1) {
  if (!/pending\w+\.current\.(?:set|add)\(requestId/.test(lines[index])) continue;
  const window = lines.slice(index, index + 6).join("\n");
  if (!window.includes("armPendingTimeout(requestId);")) unArmed.push(`${index + 1}: ${lines[index].trim()}`);
}
must("每个登记待办的点都要被超时覆盖（登记后六行内上表）", unArmed.length === 0, `漏表：${unArmed.join(" | ")}`);
must("收到带 requestId 的事件要撤表", source.includes("clearPendingTimeout(event.requestId)"));
must("事件分发入口就撤表（而不是各分支各自记着）", source.indexOf("clearPendingTimeout(event.requestId)") < source.indexOf('if (event.type === "state.snapshot")'));
must("teardown 要撤掉全部定时器", cleanup.includes("pendingTimers.current.forEach((timer) => clearTimeout(timer))"));

// ── 6) 超时**策略**：真把模块导进来跑，不是读源码猜 ──────────────────
const TEMP = join(ROOT, ".host-resync-behavior.mjs");
try {
  const entry = [
    `export { PENDING_TIMEOUT_MS, settlePolicyFor } from ${JSON.stringify(join(ROOT, "src/host/pendingTimeout.ts").replace(/\\/g, "/"))};`,
    "",
  ].join("\n");
  await writeTempEntry(entry);
  const mod = await import(pathToFileURL(TEMP).href);
  const read = mod.settlePolicyFor("read");
  const write = mod.settlePolicyFor("write");
  must("读超时必须停掉转圈", read.resetLoading === true);
  must("读超时要丢掉待办（可以直接重试）", read.forgetRequest === true);
  must("写超时**不得**回滚乐观写（结果未知不能假装失败）", write.rollback === false);
  must("写超时必须保留待办，让迟到的 error 事件仍能回滚", write.forgetRequest === false);
  must("写超时不得停掉读的转圈语义（另有文案）", write.messageKey === "error.writeUnconfirmed");
  must("读超时用「没有回应」词条", read.messageKey === "error.noResponse");
  must("超时必须是个正数", mod.PENDING_TIMEOUT_MS > 0 && mod.PENDING_TIMEOUT_MS < 5 * 60_000);
} finally {
  await rm(TEMP, { force: true }).catch(() => {});
}

if (failures.length > 0) {
  console.error("FAIL: Host 连接收尾/结算断言未通过：");
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(`OK: Host 连接收尾、结算与超时 ${PENDING_MAPS.length + LOADING_SLICES.length + 4 + 7 + 7} 条断言全部通过。`);

/** 把入口写成临时文件并用 esbuild 打包成可直接 import 的 .mjs（同其它 check 脚本的做法）。 */
async function writeTempEntry(entry) {
  await build({
    stdin: { contents: entry, resolveDir: ROOT, loader: "ts", sourcefile: "resync-entry.ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    outfile: TEMP,
    logLevel: "silent",
  });
}
