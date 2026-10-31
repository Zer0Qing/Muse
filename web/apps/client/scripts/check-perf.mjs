/**
 * 消息列表性能与动效的断言。
 *
 * 纯逻辑（memo 比较 / 快照合并 / 窗口化 / perf 开关）直接跑；CSS 侧断言
 * 「动效只用 motion 令牌」「减少动态时全部停掉」；接线（窗口化、占位、perf 日志）
 * 按源码结构断言——没有浏览器，能断言的都断了。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".perf-check.mjs");

const ENTRY = `
export { areBubblePropsEqual, areMessagesEqual, mergeMessages } from "./src/host/messageEqual.ts";
export { DEFAULT_ROW_HEIGHT, measureAverage, planWindow, shouldUpdateAverage, WINDOW_OVERSCAN, WINDOW_THRESHOLD } from "./src/host/messageWindow.ts";
export { formatPerfLine, perfFlag } from "./src/host/perf.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "perf-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    logLevel: "warning",
    outfile: TEMP,
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const BUBBLE = readFileSync(join(ROOT, "src", "host", "MessageBubble.tsx"), "utf8");

const message = (id, overrides = {}) => ({
  id,
  role: "assistant",
  content: `内容 ${id}`,
  createdAt: 1_700_000_000_000,
  ...overrides,
});

try {
  // ── 1. memo 比较：只认影响渲染的字段 ─────────────────────────
  const base = message("m1");
  assert(mod.areMessagesEqual(base, base) === true, "同引用直接判等");
  assert(mod.areMessagesEqual(base, message("m1")) === true, "内容相同的新对象判等（memo 该跳过）");
  assert(mod.areMessagesEqual(base, message("m1", { content: "改了" })) === false, "正文变了要重渲染");
  assert(mod.areMessagesEqual(base, message("m1", { status: "failed" })) === false, "状态变化要重渲染");
  assert(mod.areMessagesEqual(base, message("m1", { reasoning: "想一下" })) === false, "思考过程变化要重渲染");
  assert(mod.areMessagesEqual(base, message("m1", { quotedContent: "引用" })) === false, "引用块变化要重渲染");
  assert(mod.areMessagesEqual(base, message("m2")) === false, "不同 id 判不等");
  assert(
    mod.areMessagesEqual(
      message("m3", { imageUrls: ["/api/media/a"] }),
      message("m3", { imageUrls: ["/api/media/a"] }),
    ) === true,
    "媒体地址数组按内容比较（不是引用）",
  );
  assert(
    mod.areMessagesEqual(
      message("m3", { imageUrls: ["/api/media/a"] }),
      message("m3", { imageUrls: ["/api/media/b"] }),
    ) === false,
    "媒体地址变了要重渲染",
  );
  const call = (result) => ({
    toolCalls: [{ callId: "c1", name: "read_file", argumentsPreview: "{}", resultPreview: result, success: true, durationMs: 12 }],
  });
  assert(mod.areMessagesEqual(message("m4", call("ok")), message("m4", call("ok"))) === true, "工具调用明细相同判等");
  assert(mod.areMessagesEqual(message("m4", call("ok")), message("m4", call("换了"))) === false, "工具结果变了要重渲染");

  const caps = { known: true };
  const bubbleProps = (overrides = {}) => ({
    message: base,
    streaming: false,
    showActions: false,
    capabilities: caps,
    favorited: false,
    actionsOpen: false,
    selectionMode: false,
    ...overrides,
  });
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps()) === true, "气泡 props 没变就跳过渲染");
  assert(
    mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ onCopy: () => undefined, onAction: () => undefined })) === true,
    "回调换新引用不影响判定（回调都是稳定的，不进比较）",
  );
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ streaming: true })) === false, "流式状态变化要重渲染");
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ actionsOpen: true })) === false, "菜单开合要重渲染");
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ favorited: true })) === false, "收藏态变化要重渲染");
  assert(/export const MessageBubble = memo\(MessageBubbleView, areBubblePropsEqual\)/.test(BUBBLE), "消息组件确实挂了 memo");

  // ── 2. 快照合并：没变的消息换回旧引用 ─────────────────────────
  const first = message("a");
  const second = message("b");
  const previous = [first, second];

  const sameNext = [message("a"), message("b")];
  const mergedSame = mod.mergeMessages(previous, sameNext);
  assert(mergedSame === previous, "整份没变时直接复用旧数组");

  const streamingNext = [message("a"), message("b", { content: "流式中…" })];
  const mergedStreaming = mod.mergeMessages(previous, streamingNext);
  assert(mergedStreaming[0] === first, "没变的消息复用旧对象（memo 才有效）");
  assert(mergedStreaming[1] === streamingNext[1], "变了的那条用新对象");
  assert(mergedStreaming.length === 2 && mergedStreaming !== previous, "合并结果是新数组，不是原地改");

  const appended = mod.mergeMessages(previous, [message("a"), message("b"), message("c")]);
  assert(appended.length === 3 && appended[0] === first && appended[1] === second, "追加一条时前面的引用不变");
  assert(appended[2]?.id === "c", "新追加的消息进列表");

  const replacedFirst = mod.mergeMessages(previous, [message("a", { content: "改了" }), message("b")]);
  assert(
    replacedFirst[0] !== first && replacedFirst !== previous,
    "开头就变时只复用公共前缀（这里是空前缀），整份换新——不做跨位置的对象搬运",
  );

  assert(mod.mergeMessages([], previous) === previous, "空列表合并直接返回新快照");

  // ── 3. 窗口化：阈值与可视区计算 ──────────────────────────────
  assert(mod.WINDOW_THRESHOLD === 150 && mod.WINDOW_OVERSCAN === 20, "阈值 150、上下各 20 条");
  const small = mod.planWindow({ itemCount: 150, scrollTop: 0, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(small.active === false && small.start === 0 && small.end === 150, "不超过 150 条时全量渲染");

  const big = mod.planWindow({ itemCount: 400, scrollTop: 1000, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(big.active === true, "超过阈值启启用窗口化");
  assert(big.start === 0 && big.end === 36, `滚动 1000px / 行高 100 / 视口 600 → 渲染 [0,36)：实际 [${big.start},${big.end})`);
  assert(big.topPad === 0 && big.bottomPad === (400 - 36) * 100, "两端占位按平均行高补齐总高度");

  const middle = mod.planWindow({ itemCount: 400, scrollTop: 5000, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(middle.start === 30 && middle.end === 76, `中间位置窗口 [30,76)：实际 [${middle.start},${middle.end})`);
  assert(middle.topPad === 30 * 100, "顶部占位 = start × 行高");

  const tail = mod.planWindow({ itemCount: 400, scrollTop: 0, viewportHeight: 600, averageHeight: 100, pinnedToEnd: true });
  assert(tail.end === 400 && tail.start === 374, `贴底时锚定末尾：实际 [${tail.start},${tail.end})`);
  assert(tail.bottomPad === 0, "贴底时底部不留占位");

  const unknown = mod.planWindow({ itemCount: 400, scrollTop: 0, viewportHeight: 600, averageHeight: 0, pinnedToEnd: true });
  assert(unknown.end === 400 && unknown.start === 400 - Math.ceil(600 / mod.DEFAULT_ROW_HEIGHT) - 20, "还没量到行高时用默认估值");
  const overscroll = mod.planWindow({ itemCount: 200, scrollTop: 1e9, viewportHeight: 600, averageHeight: 50, pinnedToEnd: false });
  assert(overscroll.end === 200 && overscroll.bottomPad === 0, "滚过头时窗口夹在末尾，占位为 0");
  assert(mod.planWindow({ itemCount: 0, scrollTop: 0, viewportHeight: 0, averageHeight: 0, pinnedToEnd: false }).active === false, "空列表不启用窗口化");

  assert(mod.measureAverage([100, 200]) === 150, "平均行高按已渲染行取均值");
  assert(mod.measureAverage([]) === undefined && mod.measureAverage([0, -1]) === undefined, "没有有效行高时返回 undefined");
  assert(mod.shouldUpdateAverage(0, 80) === true, "首次量到行高就采纳");
  assert(mod.shouldUpdateAverage(100, 105) === false, "偏差 5% 不更新（死区）");
  assert(mod.shouldUpdateAverage(100, 130) === true, "偏差 30% 才更新");

  // ── 4. ?perf=1 只在带参时生效 ────────────────────────────────
  assert(mod.perfFlag("?perf=1") === true && mod.perfFlag("perf=1") === true, "带 perf=1 才打开");
  assert(mod.perfFlag("") === false && mod.perfFlag("?perf=0") === false && mod.perfFlag("?debug=1") === false, "不带参数时不打日志");
  const line = mod.formatPerfLine(120, 12.34);
  assert(line.includes("120 条消息") && line.includes("12.3ms"), `日志格式：${line}`);

  // ── 5. 接线 ───────────────────────────────────────────────
  assert(/useMessageWindow\(/.test(CHAT) && /windowPlan\.topPad/.test(CHAT) && /windowPlan\.bottomPad/.test(CHAT), "聊天页用窗口化计划渲染上下占位");
  assert(/items\.slice\(windowPlan\.start, windowPlan\.end\)/.test(CHAT), "只渲染窗口内的条目");
  assert(CHAT.includes('data-host-row'), "每行带测量标记（行高由此回填）");
  assert(/perfFlag\(/.test(CHAT) && /formatPerfLine\(/.test(CHAT) && /onSnapshot:/.test(CHAT), "?perf=1 时记录快照耗时");
  assert(/stickRef\.current,?\s*$/m.test(CHAT) || CHAT.includes("pinnedToEnd: stickRef.current"), "贴底时窗口锚在末尾");

  // ── 6. 动效与减少动态 ─────────────────────────────────────
  assert(
    /\.host-msg-row\[data-new="true"\] \{ animation: host-row-in var\(--muse-motion-durations-content-enter\)/.test(CSS),
    "新消息进入用 content-enter 时长（只给最新一条，避免窗口化滚动时反复入场）",
  );
  assert(/var\(--muse-motion-easings-emphasized\)/.test(CSS), "缓动取 motion 令牌");
  assert(/\.host-msg-status,\s*\n?\s*\.host-msg-failed \{ animation: host-status-in/.test(CSS), "状态变化有过渡");
  assert(/\.host-drawer \{[\s\S]{0,200}var\(--muse-motion-durations-nav-horizontal\)/.test(CSS), "抽屉开合时长走 motion 令牌");
  assert(/\.host-scrim \{ transition: opacity var\(--muse-motion-durations-normal\)/.test(CSS), "遮罩淡入时长走 motion 令牌");
  const reduced = CSS.slice(CSS.indexOf("@media (prefers-reduced-motion: reduce)"));
  for (const selector of [".host-msg-row", ".host-msg-status", ".host-msg-failed", ".host-drawer", ".host-scrim", ".host-picker"]) {
    assert(reduced.includes(selector), `减少动态时 ${selector} 也被关掉`);
  }
  assert(/animation: none !important/.test(reduced) && /transition: none !important/.test(reduced), "减少动态是「不动」而不是「变快」");
  assert(/\.host-window-pad \{ flex: 0 0 auto/.test(CSS), "窗口占位块不参与伸缩");

  if (failures > 0) throw new Error(`性能与动效断言未通过（${failures} 条）`);
  console.log("\n性能与动效断言全部通过（memo / 合并 / 窗口化 / perf 开关 / 动效）。");
} finally {
  await rm(TEMP, { force: true });
}
