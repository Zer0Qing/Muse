/**
 * 聊天页细节的渲染断言：时间分隔、折叠块、复制、逐条错误隔离，
 * 以及 Host 新补协议后的图片 / 语音 / 消息状态 / 重试 / 能力协商。
 *
 * 断言的都是「协议字段 → 界面」这一层，包含老 Host（字段缺失）的降级路径：
 * 缺 capabilityFlags 时按老行为兜底，显式缺某一位时才关闭对应界面。
 *
 * 用法：npm --workspace @muse/client run test
 * 机制与 check-settings-render.mjs 相同：vite 自带的 esbuild 打成临时 ESM 再 import，
 * 不新增依赖，临时文件在 finally 里删掉。
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".chat-render-check.mjs");

const ENTRY = `
export { buildStreamItems, toolStatusOf } from "./src/host/chatStream.ts";
export { formatDaySeparator, dayKey, formatToolDuration, formatVoiceDuration } from "./src/host/format.ts";
export { resolveMessageImages, resolveVoiceUrl } from "./src/host/messageMedia.ts";
export { readCapabilities } from "./src/host/capabilities.ts";
export { actionsForMessage, deleteConfirmKey, quoteExcerpt, selectionCopyText } from "./src/host/messageActions.ts";
export { MessageBubble } from "./src/host/MessageBubble.tsx";
export { MessageImages } from "./src/host/MessageImages.tsx";
export { ToolCallBlocks } from "./src/host/ToolCallBlocks.tsx";
export { VoiceBar } from "./src/host/VoiceBar.tsx";
export { ImageLightbox } from "./src/host/ImageLightbox.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "chat-render-entry.tsx", loader: "tsx" },
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
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** 固定「现在」，避免跑测时刻跨分钟让断言飘。 */
const NOW = new Date(2026, 9, 30, 14, 30, 0).getTime();

const message = (id, role, extra = {}) => ({
  id,
  role,
  content: "",
  createdAt: NOW,
  ...extra,
});

/** 老 Host：未下发 capabilityFlags。 */
const LEGACY = mod.readCapabilities(undefined);
/** 新 Host：能力位齐全。 */
const FULL = mod.readCapabilities([
  "chat",
  "streaming",
  "media",
  "tool_call_details",
  "message_status",
  "settings_values",
  "message_write",
  "message_multi_select",
]);

const render = (element) => mod.renderToStaticMarkup(element);
const bubble = (overrides) =>
  mod.createElement(mod.MessageBubble, {
    streaming: false,
    showActions: false,
    onRegenerate: () => undefined,
    onContinue: () => undefined,
    capabilities: LEGACY,
    ...overrides,
  });

try {
  // ── 1. 时间分隔：按天插，同分钟不重复打断 ─────────────────────────
  const sameDay = [
    message("u1", "user", { createdAt: NOW - 5 * MINUTE, content: "早" }),
    message("a1", "assistant", { createdAt: NOW - 4 * MINUTE, content: "嗨" }),
    message("u2", "user", { createdAt: NOW - 3 * MINUTE, content: "在吗" }),
  ];
  const items = mod.buildStreamItems(sameDay, NOW);
  const dividers = items.filter((item) => item.kind === "divider");
  assert(dividers.length === 1, "同一天内的连续消息只有一条时间分隔（顶部那条）");
  assert(dividers[0]?.label.startsWith("今天 "), `今天的分隔条走「今天 HH:mm」：${String(dividers[0]?.label)}`);
  assert(items.filter((item) => item.kind === "message").length === 3, "三条消息都进入清单");

  const crossed = [
    message("d1", "user", { createdAt: NOW - 2 * DAY }),
    message("d2", "user", { createdAt: NOW - DAY }),
    message("d3", "user", { createdAt: NOW }),
  ];
  const crossedItems = mod.buildStreamItems(crossed, NOW);
  const crossedLabels = crossedItems.filter((item) => item.kind === "divider").map((item) => item.label);
  assert(crossedLabels.length === 3, `跨天各插一条分隔条：${crossedLabels.join(" | ")}`);
  assert(crossedLabels[1]?.startsWith("昨天 "), `昨天走相对风格：${String(crossedLabels[1])}`);
  assert(/^10月28日 \d{2}:\d{2}$/.test(String(crossedLabels[0])), `更早按现有风格带日期：${String(crossedLabels[0])}`);

  // 同一分钟内的连续消息（只隔几十秒）不能被分隔条反复打断
  const sameMinute = [40, 20, 5].map((secondsAgo, index) =>
    message(`s${index}`, index % 2 === 0 ? "user" : "assistant", { createdAt: NOW - secondsAgo * 1_000 }),
  );
  const sameMinuteItems = mod.buildStreamItems(sameMinute, NOW);
  assert(
    sameMinuteItems.filter((item) => item.kind === "divider").length === 1,
    "同一分钟内的连续消息不被分隔条重复打断：只剩顶部一条",
  );

  // 退化数据：时间戳乱序回到同一天时，不会连着插两条文案相同的分隔
  const shuffled = [
    message("x1", "user", { createdAt: new Date(2026, 9, 20, 10, 0, 0).getTime() }),
    message("x2", "user", { createdAt: new Date(2026, 9, 19, 10, 0, 0).getTime() }),
    message("x3", "user", { createdAt: new Date(2026, 9, 20, 10, 0, 30).getTime() }),
  ];
  const shuffledLabels = mod
    .buildStreamItems(shuffled, NOW)
    .filter((item) => item.kind === "divider")
    .map((item) => item.label);
  assert(
    shuffledLabels.every((label, index) => index === 0 || label !== shuffledLabels[index - 1]),
    `乱序时间戳不会连着插两条同文案分隔：${shuffledLabels.join(" | ")}`,
  );

  const broken = [message("b1", "user", { createdAt: 0 }), message("b2", "assistant", { createdAt: -1 })];
  const brokenItems = mod.buildStreamItems(broken, NOW);
  assert(brokenItems.every((item) => item.kind === "message"), "时间戳非法时不产生分隔条，消息照常渲染");

  const dup = [message("same", "user"), message("same", "assistant")];
  const dupKeys = mod.buildStreamItems(dup, NOW).filter((item) => item.kind === "message").map((item) => item.key);
  assert(new Set(dupKeys).size === 2, `重复 id 的 key 会去重：${dupKeys.join(", ")}`);

  // ── 2. 工具调用：默认折叠的块，标题行带工具名与状态 ───────────────
  const call = message("t1", "assistant", { content: '{"path":"a.txt"}', toolName: "read_file", toolCallId: "call-1" });
  const status = mod.toolStatusOf(call, { isStreaming: true, isLast: true, approvalToolCallIds: new Set(["call-1"]) });
  assert(status === "awaiting", "toolCallId 命中待审批时状态是「待确认」");
  const running = mod.toolStatusOf(call, { isStreaming: true, isLast: true, approvalToolCallIds: new Set() });
  assert(running === "running", "生成中且是最后一条时状态是「执行中」");
  const done = mod.toolStatusOf(message("t2", "tool", { toolName: "read_file", toolCallId: "call-1", content: "结果" }), {
    isStreaming: false,
    isLast: false,
    approvalToolCallIds: new Set(),
  });
  assert(done === "done", "role=tool 的结果消息状态是「已完成」");

  const toolHtml = render(bubble({ message: call, toolStatus: "running" }));
  assert(toolHtml.includes("<details"), "工具调用块是 details 折叠元素");
  assert(!toolHtml.includes("<details open"), "工具调用块默认折叠");
  assert(toolHtml.includes("read_file") && toolHtml.includes("执行中"), "标题行同时给出工具名与状态");

  // ── 3. 思考过程：同款折叠，与工具块共用组件 ──────────────────────
  const thinkingHtml = render(bubble({ message: message("r1", "assistant", { content: "答案", reasoning: "先想一步" }) }));
  assert(thinkingHtml.includes("思考过程") && thinkingHtml.includes("先想一步"), "思考过程渲染成折叠块");
  assert(
    (thinkingHtml.match(/class="host-details"/g) ?? []).length === 1,
    "思考块与工具块走同一个折叠组件（host-details）",
  );

  // ── 4. 操作菜单：只给正文非空的非空菜单，项数随能力位与角色变 ────
  const menuLabels = (html) => [...html.matchAll(/<span>([^<]+)<\/span>/g)].map((match) => match[1]);
  const legacyMenu = render(
    bubble({ message: message("c1", "user", { content: "复制我" }), onCopy: () => undefined, actionsOpen: true }),
  );
  assert(legacyMenu.includes('aria-label="复制这条消息"'), "老 Host（无 message_write）只给复制");
  assert(legacyMenu.includes("is-open"), "展开状态写在气泡上，样式层据此亮出操作条");
  assert((legacyMenu.match(/host-bubble-tools/g) ?? []).length === 1, "列只能有一个操作条");
  assert(
    menuLabels(legacyMenu).filter((label) => ["复制", "引用", "收藏", "删除", "多选"].includes(label)).join(",") === "复制",
    `菜单项只有复制：${menuLabels(legacyMenu).join(",")}`,
  );
  const withoutCopy = render(bubble({ message: message("c2", "assistant", { content: "   " }), onCopy: () => undefined }));
  assert(!withoutCopy.includes("host-bubble-tools"), "没展开菜单时不渲染操作条");
  const emptyMenu = render(bubble({ message: message("c3", "user", { content: "   " }), capabilities: FULL, actionsOpen: true }));
  assert(!emptyMenu.includes('aria-label="复制这条消息"'), "空正文不给出复制项（其余写操作不受影响）");

  // ── 4b. 菜单矩阵：能力位 × 角色 × 收藏态 ────────────────────────
  const idsOf = (items) => items.map((item) => item.id).join(",");
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: LEGACY })) === "copy",
    "老 Host：菜单只有复制",
  );
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: FULL })) ===
      "copy,quote,favorite,select,delete",
    "新 Host：复制 / 引用 / 收藏 / 多选 / 删除",
  );
  assert(
    idsOf(mod.actionsForMessage({ role: "assistant", hasContent: true, favorited: true, capabilities: FULL })) ===
      "copy,quote,unfavorite,select,delete",
    "已收藏时菜单项变成「取消收藏」",
  );
  const writeOnly = { ...FULL, multiSelect: false };
  assert(
    !idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: writeOnly })).includes("select"),
    "缺 message_multi_select 不出现多选入口",
  );
  const selectOnly = { ...FULL, messageWrite: false };
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: selectOnly })) === "copy,select",
    "缺 message_write 时只留复制（多选入口仍在，批量动作由操作条自己把关）",
  );
  for (const role of ["tool", "system"]) {
    assert(
      idsOf(mod.actionsForMessage({ role, hasContent: true, favorited: false, capabilities: FULL })) === "copy",
      `${role} 消息只给复制`,
    );
  }
  const favoriteMenu = render(
    bubble({
      message: message("c4", "user", { content: "收藏我" }),
      capabilities: FULL,
      favorited: true,
      actionsOpen: true,
    }),
  );
  assert(favoriteMenu.includes("取消收藏") && !favoriteMenu.includes(">收藏<"), "已收藏时渲染「取消收藏」而不是「收藏」");
  assert(favoriteMenu.includes("引用") && favoriteMenu.includes("多选") && favoriteMenu.includes("删除"), "五项菜单都出现在展开的操作条里");
  assert(favoriteMenu.includes("is-danger"), "删除项带破坏性样式标记");
  const toolMenu = render(
    bubble({
      message: message("c5", "tool", { content: "工具结果", toolName: "read_file" }),
      capabilities: FULL,
      actionsOpen: true,
    }),
  );
  assert(toolMenu.includes("host-tool is-open") && menuLabels(toolMenu).includes("复制"), "tool 消息展开后也只有复制");
  assert(!toolMenu.includes("引用"), "tool 消息不给引用");

  // ── 5. 媒体地址归一：新 Host 相对路径 + 老数据四种形态 ────────────
  const images = mod.resolveMessageImages([
    "https://example.com/a.png",
    "data:image/png;base64,AAA",
    "A".repeat(80),
    "file:///data/user/0/io.zer0.muse/files/images/x.bin",
    "/api/media/tok-1",
  ]);
  assert(images[0]?.kind === "src" && images[0].src === "https://example.com/a.png", "http 地址直接用");
  assert(images[1]?.kind === "src" && images[1].src.startsWith("data:"), "data URI 直接用");
  assert(images[2]?.kind === "src" && images[2].src.startsWith("data:image/png;base64,"), "裸 base64 补齐 data 前缀");
  assert(images[3]?.kind === "unavailable", "file:// 本地路径判定为拿不到");
  assert(images[4]?.kind === "src" && images[4].src === "/api/media/tok-1", "新 Host 的 /api/media/<token> 直接当同源 src");
  assert(mod.resolveVoiceUrl("/api/media/voice-1") === "/api/media/voice-1", "语音地址接受同源 /api/media 路径");
  assert(mod.resolveVoiceUrl("file:///data/voice.m4a") === undefined, "语音的 file:// 引用判定为取不到");

  // ── 6. 图片消息：单图/多图、点击全屏、加载失败占位、media 能力位 ──
  const imageHtml = render(
    bubble({ message: message("i1", "assistant", { content: "看图", imageUrls: ["/api/media/tok-1"] }), capabilities: FULL }),
  );
  assert(imageHtml.includes('src="/api/media/tok-1"'), "imageUrls 直接渲染同源相对路径");
  assert(imageHtml.includes('aria-label="查看大图"') && imageHtml.includes('class="quiet host-bubble-image"'), "图片可点击进全屏查看");
  assert(!imageHtml.includes("is-grid"), "单图不套多图网格");
  const multiImageHtml = render(
    bubble({
      message: message("i2", "assistant", { imageUrls: ["/api/media/a", "/api/media/b"] }),
      capabilities: FULL,
    }),
  );
  assert(multiImageHtml.includes("host-bubble-images is-grid"), "多图走两列格子");

  const fileImageHtml = render(
    bubble({
      message: message("i3", "assistant", { imageUrls: ["file:///data/x.bin"] }),
      capabilities: FULL,
    }),
  );
  assert(fileImageHtml.includes("host-bubble-image-missing") && !fileImageHtml.includes("<img"), "取不到的图片给占位而不是破图");

  const noMediaHtml = render(
    bubble({
      message: message("i4", "assistant", { imageUrls: ["/api/media/tok-1"], voiceUrl: "/api/media/v", voiceDurationMs: 5_000 }),
      capabilities: mod.readCapabilities(["chat", "streaming"]),
    }),
  );
  assert(!noMediaHtml.includes("host-bubble-image"), "缺 media 能力位时不渲染图片");
  assert(!noMediaHtml.includes("host-voice"), "缺 media 能力位时不渲染语音");

  const lightboxHtml = render(mod.createElement(mod.ImageLightbox, { src: "/api/media/tok-1", onClose: () => undefined }));
  assert(lightboxHtml.includes('role="dialog"') && lightboxHtml.includes('aria-modal="true"'), "全屏查看是有对话框语义的浮层");
  assert(lightboxHtml.includes('src="/api/media/tok-1"') && lightboxHtml.includes('aria-label="关闭预览"'), "浮层给出图片与关闭入口");

  // ── 7. 语音消息：可播放 vs 只有时长 ─────────────────────────────
  const voiceHtml = render(
    bubble({
      message: message("v1", "assistant", { voiceUrl: "/api/media/voice-1", voiceDurationMs: 12_000 }),
      capabilities: FULL,
    }),
  );
  assert(voiceHtml.includes("<audio") && voiceHtml.includes('src="/api/media/voice-1"'), "有 voiceUrl 时渲染 audio");
  assert(voiceHtml.includes('aria-label="播放语音"'), "语音条给播放入口");
  assert(voiceHtml.includes("12″"), "语音条显示 durationMs 换算的时长");
  assert(mod.formatVoiceDuration(12_000) === "12″" && mod.formatVoiceDuration(90_000) === "1′30″", "时长格式化：秒 / 分秒");

  const staticVoiceHtml = render(
    bubble({ message: message("v2", "assistant", { voiceDurationMs: 7_000 }), capabilities: FULL }),
  );
  assert(!staticVoiceHtml.includes("<audio"), "没有 voiceUrl 时不挂 audio");
  assert(staticVoiceHtml.includes("host-voice is-static") && staticVoiceHtml.includes("语音 · 7″"), "只有时长时降级成静态条");

  // ── 8. 消息状态：发送中 / 失败 + 重试 / 能力位关闭 ────────────────
  const sendingHtml = render(
    bubble({ message: message("st1", "user", { content: "在吗", status: "sending" }), capabilities: FULL }),
  );
  assert(sendingHtml.includes("发送中…") && sendingHtml.includes("host-msg-status"), "status=sending 显示轻量发送中");

  const failedHtml = render(
    bubble({
      message: message("st2", "user", { content: "在吗", status: "failed", errorText: "手机没有网络" }),
      capabilities: FULL,
      onRetry: () => undefined,
    }),
  );
  assert(failedHtml.includes("手机没有网络"), "failed 时在气泡内展示 errorText");
  assert(failedHtml.includes("host-retry") && failedHtml.includes(">重试<"), "failed 时给出「重试」按钮");
  assert(failedHtml.includes('role="alert"'), "失败行是 alert 语义");

  const failedNoText = render(
    bubble({ message: message("st3", "user", { status: "failed" }), capabilities: FULL, onRetry: () => undefined }),
  );
  assert(failedNoText.includes("发送失败"), "errorText 缺失时回退到「发送失败」");

  const noStatusCapability = render(
    bubble({
      message: message("st4", "user", { status: "failed", errorText: "手机没有网络" }),
      capabilities: mod.readCapabilities(["media"]),
      onRetry: () => undefined,
    }),
  );
  assert(
    !noStatusCapability.includes("host-msg-failed") && !noStatusCapability.includes("host-retry"),
    "缺 message_status 能力位时不显示状态与重试",
  );
  const sentHtml = render(bubble({ message: message("st5", "user", { content: "已发", status: "sent" }), capabilities: FULL }));
  assert(!sentHtml.includes("host-msg-status"), "status=sent 不额外占位");

  // ── 9. 工具调用明细：toolCalls[] 优先，缺字段回退旧推断 ───────────
  const detailedCalls = [
    { callId: "c1", name: "read_file", argumentsPreview: '{"path":"a.txt"}', resultPreview: "hello", success: true, durationMs: 1_200 },
    { callId: "c2", name: "shell_exec", success: false, durationMs: 300 },
  ];
  const callsHtml = render(
    bubble({ message: message("tc1", "assistant", { content: "查一下", toolCalls: detailedCalls }), capabilities: FULL }),
  );
  assert((callsHtml.match(/class="host-details"/g) ?? []).length === 2, "每个工具调用渲染一块折叠");
  assert(callsHtml.includes("read_file") && callsHtml.includes("shell_exec"), "每块标题用调用自己的名字");
  assert(callsHtml.includes("成功 · 1.2s") && callsHtml.includes("失败 · 0.3s"), "标题行给出成功与否与耗时");
  assert(callsHtml.includes("参数") && callsHtml.includes("hello"), "展开区显示参数与结果预览");
  assert(!callsHtml.includes("<details open"), "工具明细默认折叠");

  const callsWithoutCapability = render(
    bubble({
      message: message("tc2", "assistant", { content: "查一下", toolCalls: detailedCalls }),
      capabilities: mod.readCapabilities(["chat"]),
    }),
  );
  assert(!callsWithoutCapability.includes("read_file"), "缺 tool_call_details 时不渲染明细块");
  const legacyToolHtml = render(
    bubble({
      message: message("tc3", "assistant", { content: "查一下", toolName: "read_file", toolCallId: "c9" }),
      capabilities: mod.readCapabilities(["chat"]),
      toolStatus: "requested",
    }),
  );
  assert(legacyToolHtml.includes("工具调用 · read_file"), "toolCalls 为空时回退到 toolName 推断块");

  // ── 10. 能力协商：老 Host 不退化成全隐藏 ─────────────────────────
  assert(LEGACY.known === false && LEGACY.media === true, "未下发 capabilityFlags 时按老行为兜底（不隐藏媒体）");
  assert(FULL.known === true && FULL.media && FULL.toolCallDetails && FULL.messageStatus, "三项能力位都能被识别");
  assert(mod.readCapabilities(["media"]).messageStatus === false, "缺位即关闭");
  const legacyImageHtml = render(
    bubble({ message: message("lg1", "assistant", { imageUrls: ["/api/media/tok-1"] }), capabilities: LEGACY }),
  );
  assert(legacyImageHtml.includes('src="/api/media/tok-1"'), "老 Host 下仍渲染能取到的媒体地址");

  // ── 10b. 引用：气泡内的引用块 + 输入区上的草稿条 ──────────────────
  const quotedHtml = render(
    bubble(
      {
        message: message("q1", "user", { content: "这是我的回答", quotedContent: "PROTO-被引用的原文" }),
        capabilities: FULL,
      },
    ),
  );
  assert(quotedHtml.includes("host-quote") && quotedHtml.includes("PROTO-被引用的原文"), "quotedContent 渲染成气泡内的引用块");
  const noQuoteHtml = render(bubble({ message: message("q2", "user", { content: "没有引用" }), capabilities: FULL }));
  assert(!noQuoteHtml.includes("host-quote"), "Host 不下发 quotedContent 时不显示引用块（降级）");
  assert(mod.quoteExcerpt("  第一行\n\n第二行  ") === "第一行 第二行", "引用摘要压平换行与多余空白");
  assert(mod.quoteExcerpt("x".repeat(120), 20) === `${"x".repeat(20)}…`, "引用摘要超长截断并加省略号");

  // ── 10c. 多选与批量：纯逻辑 + 接线 ──────────────────────────────
  assert(mod.selectionCopyText([{ content: "a" }, { content: "  " }, { content: "b" }]) === "a\n\nb", "多选复制只拼有正文的消息");
  assert(
    mod.deleteConfirmKey(1) === "message.deleteConfirmOne",
    `单条删除用单条词条：${mod.deleteConfirmKey(1)}`,
  );
  assert(
    mod.deleteConfirmKey(3) === "message.deleteConfirmMany",
    `批量删除用批量词条：${mod.deleteConfirmKey(3)}`,
  );

  // ── 11. 接线与样式：宿主把能力位、重试、全屏都接上 ────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of [
    "host-day-sep",
    "host-details-status",
    "host-bubble-tools",
    "host-msg-row",
    "host-select",
    "host-selection-bar",
    "host-quote",
    "host-quote-draft",
    "host-bubble-image",
    "host-bubble-image-missing",
    "host-voice",
    "host-tool-field",
    "host-msg-status",
    "host-msg-failed",
    "host-lightbox",
    "host-msg-error",
  ]) {
    assert(css.includes(`.${selector}`), `样式里定义了 .${selector}`);
  }
  const chatSource = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  assert(
    chatSource.includes("<MessageErrorBoundary") && chatSource.includes("buildStreamItems"),
    "消息清单逐条包在错误隔离里，分隔条来自 buildStreamItems",
  );
  assert(
    chatSource.includes('className="host-day-sep"') && chatSource.includes('role="separator"'),
    "分隔条以 separator 语义进入消息流",
  );
  assert(chatSource.includes("copyTextToClipboard"), "复制走带兜底的剪贴板工具（局域网 http 无 Clipboard API）");
  assert(
    chatSource.includes("readCapabilities") && chatSource.includes("capabilities={capabilities}"),
    "能力位由快照读出并传给每条消息",
  );
  assert(
    chatSource.includes('type: "chat.retry", messageId'),
    "重试发 chat.retry 并带上这条消息的 messageId",
  );
  assert(chatSource.includes("<ImageLightbox"), "全屏查看挂在这个聊天页上");
  const imagesSource = readFileSync(join(ROOT, "src", "host", "MessageImages.tsx"), "utf8");
  assert(imagesSource.includes("onError"), "图片加载失败会切到占位（不是留一个破图）");

  // ── 11b. 多选 / 写操作 / 引用在聊天壳里的接线 ────────────────────
  assert(
    chatSource.includes("host-selection-bar") &&
      chatSource.includes('t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })'),
    "多选时底部固定操作条 + 顶栏显示已选条数（数字按当前语言分组）",
  );
  assert(chatSource.includes('t("chat.selectAll")') && chatSource.includes('t("chat.exitSelection")'), "顶栏操作条有全选与退出",
  );
  assert(
    chatSource.includes('className={selectedIds.includes(item.message.id) ? "host-select is-on" : "host-select"}') &&
      chatSource.includes('role="checkbox"'),
    "多选时每条消息左侧渲染勾选框",
  );
  assert(
    chatSource.includes("deleteConfirmKey(ids.length)") && chatSource.includes("window.confirm"),
    "删除前先弹二次确认（文案带「只删本机记录，无法恢复」）",
  );
  assert(
    chatSource.includes('{ type: "message.delete", messageIds: [...ids] }') &&
      chatSource.includes("setRemovedIds((current) => current.filter((id) => !ids.includes(id)))"),
    "删除走 message.delete，Host 拒绝时把隐藏的消息放回去",
  );
  assert(
    chatSource.includes("event.skippedMessageIds") && chatSource.includes('t("chat.deleteSkipped", { count: skipped.length })'),
    "回执里被跳过的消息会恢复显示（不只依赖 error）",
  );
  assert(
    chatSource.includes('sendOptimistic({ type: "message.favorite", messageId, favored: next }') &&
      chatSource.includes("delete restored[messageId]"),
    "收藏先本地翻转，Host 拒绝时撤销乐观值",
  );
  assert(
    chatSource.includes("quoteExcerpt(message.content)") && chatSource.includes("host-quote-draft") &&
      chatSource.includes("setQuoteDraft(undefined)"),
    "引用草稿条可取消",
  );
  assert(
    chatSource.includes("quotedMessageId: quoted.messageId"),
    "发送时随 chat.send 带上被引用消息的 id（字段名 quotedMessageId）",
  );
  assert(
    !/\{ messageId: quoted\.messageId \}/.test(chatSource),
    "chat.send 不再使用旧的 messageId 字段名",
  );
  const apiSource = readFileSync(join(ROOT, "src", "api.ts"), "utf8");
  assert(
    /readonly quotedMessageId\?: string;/.test(apiSource),
    "HostCommand 声明了 quotedMessageId",
  );
  assert(
    chatSource.includes('capabilities.messageWrite && (') && chatSource.includes("host-selection-danger"),
    "批量操作条里的删除/收藏也按 message_write 能力位把关",
  );
  assert(
    chatSource.includes("favorited={isFavorited(item.message.id, item.message.favorite)}"),
    "收藏态由 Host 字段 + 本地乐观覆盖共同决定",
  );
  const bubbleSource = readFileSync(join(ROOT, "src", "host", "MessageBubble.tsx"), "utf8");
  assert(
    bubbleSource.includes("actionsForMessage(") && bubbleSource.includes("onAction?.(message.id, action.id)"),
    "气泡不自己判菜单项，交给 actionsForMessage 并把选择上报",
  );

  if (failures > 0) throw new Error(`聊天渲染断言未通过（${failures} 条）`);
  console.log("\n聊天渲染断言全部通过（时间分隔 / 折叠块 / 菜单矩阵 / 引用 / 多选 / 图片 / 语音 / 状态 / 能力协商）。");
} finally {
  await rm(TEMP, { force: true });
}
