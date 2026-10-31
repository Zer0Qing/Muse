/**
 * 质量与容错的静态断言：断线三态、骨架延迟、区块错误边界、占位页、无障碍、对比度。
 *
 * 与其它脚本同一套路：纯函数直接跑，组件用 renderToStaticMarkup 渲染，
 * 接线类的东西（错误边界、焦点陷阱、aria-label）按源码结构断言 —— 没有浏览器、
 * 也不引 jsdom，能断言的就说清楚，不能断言的留给人工验证。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync, readdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".quality-check.mjs");
const TOKENS = JSON.parse(readFileSync(join(ROOT, "src", "design", "tokens.json"), "utf8"));
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const ENTRY = `
export { connectionBanner, composerDisabledHint, MANUAL_RETRY_AFTER } from "./src/host/connectionStatus.ts";
export { isSkeletonDue, SKELETON_DELAY_MS } from "./src/host/useDelayedFlag.ts";
export { trapNextIndex } from "./src/host/ImageLightbox.tsx";
export { HostPlaceholder } from "./src/host/HostPlaceholder.tsx";
export { MessageBubble } from "./src/host/MessageBubble.tsx";
export { readCapabilities } from "./src/host/capabilities.ts";
export { readLanguage } from "./src/host/useLocale.ts";
export { I18nProvider } from "./src/i18n/I18nProvider.tsx";
export { translate } from "./src/i18n/translate.ts";
export { ZH, EN, MESSAGES } from "./src/i18n/messages.ts";
export { resolveLocale, LOCALES, FALLBACK_LOCALE, DEFAULT_LOCALE } from "./src/i18n/locale.ts";
export { formatRelativeTime, formatNumber, formatMonthDay, formatDayLabel } from "./src/i18n/format.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "quality-entry.tsx", loader: "tsx" },
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

// ── WCAG 对比度（自己算，不引工具） ────────────────────────────────
function parseColor(value) {
  const text = String(value).trim();
  const hex = text.match(/^#([0-9a-fA-F]{6})$/);
  if (hex !== null) {
    const n = Number.parseInt(hex[1], 16);
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 };
  }
  const rgba = text.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/);
  if (rgba !== null) {
    return { r: Number(rgba[1]), g: Number(rgba[2]), b: Number(rgba[3]), a: rgba[4] === undefined ? 1 : Number(rgba[4]) };
  }
  throw new Error(`无法解析颜色：${text}`);
}

function over(fg, bg) {
  const alpha = fg.a ?? 1;
  return {
    r: fg.r * alpha + bg.r * (1 - alpha),
    g: fg.g * alpha + bg.g * (1 - alpha),
    b: fg.b * alpha + bg.b * (1 - alpha),
    a: 1,
  };
}

function luminance(color) {
  const channel = (value) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
}

function contrast(fgToken, bgToken) {
  const bg = parseColor(bgToken);
  const fg = over(parseColor(fgToken), bg);
  const l1 = luminance(fg);
  const l2 = luminance(bg);
  const [light, dark] = l1 >= l2 ? [l1, l2] : [l2, l1];
  return (light + 0.05) / (dark + 0.05);
}

const mod = await load();

try {
  // ── 1. 断线三态与输入禁用说明 ───────────────────────────────────
  // 纯逻辑只给「状态 → 词条 key + 参数」，文案由组件按当前语言 resolve；
  // 所以这里断言两件事：映射到的 key 对不对，以及该 key 的 zh 文案还是原来那句。
  const zh = (key, params) => mod.translate("zh", key, params);
  const offline = mod.connectionBanner({ online: false, status: "reconnecting", attempt: 2, everConnected: true });
  assert(offline.visible && offline.textKey === "connection.offline", `离线时走「网络已断开」词条：${offline.textKey}`);
  assert(zh(offline.textKey) === "网络已断开", `离线时文案 resolve 后是「网络已断开」：${zh(offline.textKey)}`);
  assert(!offline.retry, "离线时不给手动重试（重试也连不上）");

  const online1 = mod.connectionBanner({ online: true, status: "connected", attempt: 0, everConnected: true });
  assert(!online1.visible, "连上后断线条消失");

  const first = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 0, everConnected: false });
  assert(first.visible && first.textKey === "connection.connecting", `首次连接走 connection.connecting：${first.textKey}`);
  assert(zh(first.textKey) === "正在连接手机…", `首次连接文案：${zh(first.textKey)}`);
  assert(!first.retry, "前 2 次重连不给手动重试");

  const tries2 = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 2, everConnected: true });
  assert(tries2.textKey === "connection.reconnecting", `重连走 connection.reconnecting：${tries2.textKey}`);
  assert(zh(tries2.textKey, tries2.params) === "正在重连（第 2 次）", `重连文案带次数：${zh(tries2.textKey, tries2.params)}`);
  assert(!tries2.retry, "第 2 次仍不给手动重试");

  const tries3 = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 3, everConnected: true });
  assert(zh(tries3.textKey, tries3.params) === "正在重连（第 3 次）" && tries3.retry, "重连满 3 次出现手动重试");

  const closed = mod.connectionBanner({ online: true, status: "closed", attempt: 3, everConnected: true });
  assert(closed.textKey === "connection.disconnected", `断开走 connection.disconnected：${closed.textKey}`);
  assert(zh(closed.textKey) === "已断开与手机的连接" && closed.retry, `断开文案：${zh(closed.textKey)}`);
  assert(mod.MANUAL_RETRY_AFTER === 3, "手动重试阈值是 3 次");

  const hintText = (key) => (key === undefined ? "" : zh(key));
  const hintOffline = mod.composerDisabledHint({ online: false, status: "connected" });
  assert(hintOffline === "composer.hintOffline", `离线时输入框说明走 composer.hintOffline：${hintOffline}`);
  assert(hintText(hintOffline).includes("网络已断开"), `离线时输入框说明是「网络已断开」：${hintText(hintOffline)}`);
  const hintReconnecting = mod.composerDisabledHint({ online: true, status: "reconnecting" });
  assert(hintReconnecting === "composer.hintReconnecting", `重连中输入框说明走 composer.hintReconnecting：${hintReconnecting}`);
  assert(hintText(hintReconnecting).includes("正在重连"), `重连中输入框说明是「正在重连」：${hintText(hintReconnecting)}`);
  const hintClosed = mod.composerDisabledHint({ online: true, status: "closed" });
  assert(hintClosed === "composer.hintClosed", `断开后输入框说明走 composer.hintClosed：${hintClosed}`);
  assert(hintText(hintClosed).includes("已断开"), `断开后输入框说明是「已断开」：${hintText(hintClosed)}`);
  assert(mod.composerDisabledHint({ online: true, status: "connected" }) === undefined, "连接正常时输入框可用");

  // ── 2. 骨架延迟 300ms ──────────────────────────────────────────
  assert(mod.SKELETON_DELAY_MS === 300, "骨架延迟常量是 300ms");
  assert(!mod.isSkeletonDue(false, 10_000), "不在加载中就不该出骨架");
  assert(!mod.isSkeletonDue(true, 120), "120ms 内不出骨架（避免闪一下）");
  assert(mod.isSkeletonDue(true, 300), "满 300ms 才出骨架");
  for (const file of ["HostChat.tsx", "HostSettings.tsx", "SettingsValuePage.tsx", "SessionDrawer.tsx"]) {
    const source = readFileSync(join(ROOT, "src", "host", file), "utf8");
    assert(source.includes("useDelayedFlag("), `${file} 的骨架走 useDelayedFlag 延迟`);
  }
  assert(/\.host-loading-quiet\b/.test(CSS), "延迟期间给的是安静占位（不是空白也更不是骨架）");

  // ── 3. 区块错误边界 ────────────────────────────────────────────
  const boundary = readFileSync(join(ROOT, "src", "host", "BlockBoundary.tsx"), "utf8");
  assert(boundary.includes("getDerivedStateFromError") && boundary.includes("componentDidCatch"), "区块边界实现了两个错误钩子");
  assert(boundary.includes("重新加载这一块"), "区块边界提供「重新加载这一块」");
  assert(/<Fragment key=\{this\.state\.nonce\}>/.test(boundary), "重新加载 = 换 key 重挂载这一块");
  assert(/role="alert"/.test(boundary), "区块错误有 alert 语义");
  const chat = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  // 区块名字也是词条：断言「哪一块包了边界」时看的是它取的 key，再校对 key 的文案没变。
  for (const [key, copy] of [["block.messages", "消息列表"], ["block.sessions", "会话列表"], ["block.settings", "设置页"]]) {
    assert(mod.ZH[key] === copy, `词条 ${key} 是「${copy}」`);
    const wrapped = new RegExp(`<BlockBoundary name=\\{t\\("${key.replace(/\./g, "\\.")}"\\)\\}`);
    assert(wrapped.test(chat), `「${copy}」单独包了一层区块边界（名字走词条）`);
  }
  assert(chat.includes("<MessageErrorBoundary"), "逐条消息的隔离还在（区块边界不替代它）");

  // 未知 role / 未知附件形态：一行中性展示，不抛异常
  const full = mod.readCapabilities(["media", "tool_call_details", "message_status", "message_write", "message_multi_select"]);
  const renderMessage = (overrides) =>
    mod.renderToStaticMarkup(
      mod.createElement(mod.MessageBubble, {
        message: { id: "m1", role: "assistant", content: "正文", createdAt: Date.now(), ...overrides },
        streaming: false,
        showActions: false,
        onRegenerate: () => undefined,
        onContinue: () => undefined,
        capabilities: full,
      }),
    );
  const unknownRole = renderMessage({ role: "moderator" });
  assert(unknownRole.includes("host-msg-unknown") && unknownRole.includes("未知类型的消息"), "未知 role 降级成一行中性展示");
  assert(unknownRole.includes("正文"), "未知 role 仍然把正文显示出来（不丢内容）");
  assert(renderMessage({ imageUrls: ["weird://x"] }).includes("这个附件网页端打不开"), "未知附件形态给中性提示");
  assert(renderMessage({ imageUrls: ["file:///data/user/0/x.bin"] }).includes("图片存在手机上"), "老数据的本地路径给对应提示");

  // ── 4. 统一占位页 ─────────────────────────────────────────────
  const placeholder = mod.renderToStaticMarkup(
    mod.createElement(mod.HostPlaceholder, { title: "PROTO-无障碍", description: "PROTO-这个功能是做什么的", onBack: () => undefined }),
  );
  assert(placeholder.includes("这个功能需要在手机上操作"), "占位页说明需要在手机上操作");
  assert(placeholder.includes("PROTO-这个功能是做什么的"), "占位页用 Host 下发的说明交代功能");
  assert(placeholder.includes(">返回<"), "占位页有返回按钮");
  assert(!/muse-capsule[^"]*is-primary/.test(placeholder), "占位页不放主操作（假装有事可做）");
  const placeholderNoDesc = mod.renderToStaticMarkup(
    mod.createElement(mod.HostPlaceholder, { title: "PROTO-终端", onBack: () => undefined }),
  );
  assert(placeholderNoDesc.includes("只能在手机上的 Muse 里使用"), "没有说明时给一句中性话，不是空白");
  const settingsSource = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(
    settingsSource.includes("<HostPlaceholder") && !settingsSource.includes("这个页面还在迁移中"),
    "未实现的设置子页与平台专有页统一走 HostPlaceholder",
  );

  // ── 5. 无障碍：图标按钮的 aria-label ───────────────────────────
  const iconClasses = ["host-icon-button", "host-lightbox-close", "host-select", "host-session-more", "host-sidebar-collapse", "host-quote-draft-close", "host-sidebar-toggle"];
  // 按类名 token 边界匹配：host-select 不能把 host-selection-all 也算成图标按钮。
  const hasIconClass = (tag) => iconClasses.some((cls) => new RegExp(`(^|[\\s"'])${cls}([\\s"']|$)`).test(tag));
  const hostDir = join(ROOT, "src", "host");
  const offenders = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx"))) {
    const source = readFileSync(join(hostDir, file), "utf8");
    for (const match of source.matchAll(/<button\b[\s\S]{0,400}?>/g)) {
      const tag = match[0];
      if (!hasIconClass(tag)) continue;
      if (!tag.includes("aria-label")) offenders.push(`${file}: ${tag.split("\n")[0]}`);
    }
  }
  assert(offenders.length === 0, `图标按钮都有 aria-label${offenders.length === 0 ? "" : `（缺：${offenders.join(" / ")}）`}`);
  assert(
    chat.includes('aria-label={t("chat.exitSelection")}') && chat.includes('aria-label={t("chat.cancelQuote")}'),
    "多选与引用条上的图标按钮有 aria-label（走词条）",
  );
  assert(
    mod.ZH["chat.exitSelection"] === "退出多选" && mod.ZH["chat.cancelQuote"] === "取消引用",
    "退出多选 / 取消引用的词条文案仍在",
  );

  // ── 6. 焦点陷阱与 Esc ─────────────────────────────────────────
  assert(mod.trapNextIndex(3, 2, false) === 0, "Tab 在最后一个控件回到第一个");
  assert(mod.trapNextIndex(3, 0, true) === 2, "Shift+Tab 在第一个控件绕到最后一个");
  assert(mod.trapNextIndex(3, 1, false) === 2, "中间位置按自然顺序前进");
  assert(mod.trapNextIndex(0, -1, false) === -1, "没有可聚焦控件时不做任何事");
  const lightbox = readFileSync(join(ROOT, "src", "host", "ImageLightbox.tsx"), "utf8");
  assert(lightbox.includes('event.key === "Escape"'), "对话框支持 Esc 关闭");
  assert(/previous\?\.focus\(\)/.test(lightbox), "关闭后把焦点还给原来的元素");
  assert(/closeRef\.current\?\.focus\(\)/.test(lightbox) && lightbox.includes("querySelectorAll"), "打开时把焦点移进对话框并循环 Tab");
  assert(/aria-modal="true"/.test(lightbox) && /role="dialog"/.test(lightbox), "对话框语义完整");
  assert(/\.host-scope :focus-visible/.test(CSS) && /outline: var\(--muse-icons-progress-stroke\) solid var\(--accent\)/.test(CSS), "键盘焦点用主色描边（没有 outline: none）");

  // ── 7. 对比度：正文与次要文字在两种主题下都过 AA ────────────────
  assert(/--ink-muted:\s*var\(--ui-label-ink\)/.test(CSS), "次要文字别名改成 onSurface@80%（不再用中灰 outline）");
  for (const mode of ["light", "dark"]) {
    const scheme = TOKENS.colors.scheme[mode];
    const pairs = [
      ["正文 ink", scheme.onSurface, scheme.surface],
      ["正文 ink（卡片变体底）", scheme.onSurface, scheme.surfaceVariant],
      ["次要 ink-soft", scheme.onSurfaceVariant, scheme.surface],
      ["次要 ink-muted", scheme.onSurfaceVeil80, scheme.surface],
      ["次要 ink-muted（卡片变体底）", scheme.onSurfaceVeil80, scheme.surfaceVariant],
    ];
    for (const [label, fg, bg] of pairs) {
      const ratio = contrast(fg, bg);
      assert(ratio >= 4.5, `${mode} · ${label} 对比度 ${ratio.toFixed(2)}:1 ≥ 4.5`);
    }
  }

  // ── 8. 语言解析 / 词条完整性 / 时间与数字的本地化 ────────────────
  // 语言值来自 settings.snapshot.values 里含 language 的那一项；Host 没下发时退回 display.language。
  const settingsWith = (values) => ({
    snapshot: {
      groups: [],
      toggles: [],
      writableKeys: [],
      display: { language: "en", themeMode: "system", fontSizeScale: "1", themeId: "default" },
      ...(values === undefined ? {} : { values }),
    },
    loading: false,
  });
  const languageValue = (key, value) => [{ key, type: "enum", value }];
  assert(mod.readLanguage(settingsWith(languageValue("language", "ja"))) === "ja", "语言取自 settings.snapshot.values 的 language 项");
  assert(mod.readLanguage(settingsWith(languageValue("display.language", "ko"))) === "ko", "键带前缀也认得出来（normalizeSettingKey 归一）");
  assert(mod.readLanguage(settingsWith(undefined)) === "en", "Host 没下发 values 时退回 display.language");
  assert(mod.readLanguage({ loading: true }) === undefined, "设置还没到手时没有语言值");

  assert(mod.resolveLocale("ja", ["zh-CN"]) === "ja", "设置里指定了语言就按它，不看浏览器");
  assert(mod.resolveLocale("system", ["ja-JP", "en-US"]) === "ja", "system 按浏览器语言列表逐个匹配");
  assert(mod.resolveLocale(undefined, ["en-GB"]) === "en", "没有语言值时按浏览器语言");
  assert(mod.FALLBACK_LOCALE === "zh" && mod.resolveLocale("system", []) === "zh", "认不出的语言回落 zh（与手机端一致）");
  assert(mod.resolveLocale("fr", ["fr-FR"]) === "zh", "Host 给了不认识的语言值时也是 zh 兜底");
  assert(mod.LOCALES.join(",") === "zh,en,ja,ko,ru", "支持 zh / en / ja / ko / ru 五种语言");

  const zhKeys = Object.keys(mod.ZH);
  const enKeys = Object.keys(mod.EN);
  assert(zhKeys.length > 0 && enKeys.length === zhKeys.length && zhKeys.every((key) => enKeys.includes(key)), `zh / en 词条一一对应（${zhKeys.length} 条）`);
  assert(zhKeys.every((key) => mod.ZH[key].length > 0 && (mod.EN[key] ?? "").length > 0), "没有空词条");
  const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
  assert(zhKeys.every((key) => slots(mod.ZH[key]) === slots(mod.EN[key] ?? "")), "zh / en 的占位符一致");
  // ja / ko / ru 都是全量词条，各自用自己的；只有单条缺失才回落 en。
  for (const locale of ["ja", "ko", "ru"]) {
    const dict = mod.MESSAGES[locale] ?? {};
    assert(
      zhKeys.every((key) => typeof dict[key] === "string" && dict[key].length > 0),
      `${locale} 与 zh 键集一致且没有空词条`,
    );
    assert(zhKeys.every((key) => slots(dict[key] ?? "") === slots(mod.ZH[key])), `${locale} 的占位符与 zh 一致`);
    assert(mod.translate(locale, "connection.offline") === dict["connection.offline"], `${locale} 用自己的词条而不是 en`);
    assert(dict["connection.offline"] !== mod.EN["connection.offline"], `${locale} 的「无网络」确实翻译了`);
  }
  assert(mod.translate("en", "connection.reconnecting", { attempt: 4 }).includes("4"), "参数在 en 里同样被替换");

  // 语言值一变就换文案：同一棵树套不同 locale，渲染结果跟着变，不需要刷新。
  const placeholderFor = (locale) =>
    mod.renderToStaticMarkup(
      mod.createElement(mod.I18nProvider, { locale }, mod.createElement(mod.HostPlaceholder, { title: "PROTO-T", onBack: () => undefined })),
    );
  assert(placeholderFor("zh").includes("这个功能需要在手机上操作"), "zh 渲染中文");
  assert(placeholderFor("en").includes("This needs to be done on your phone"), "en 渲染英文");
  assert(placeholderFor("ja").includes("この機能はスマートフォンでの操作が必要です"), "ja 渲染日文");
  assert(placeholderFor("ru").includes("Для этой функции нужен телефон"), "ru 渲染俄文");
  assert(!placeholderFor("ru").includes("settings.placeholderTitle"), "缺词条时绝不露出 key 名");
  assert(chat.includes("<I18nProvider locale={locale}>") && chat.includes("useHostLocale(settings)"), "聊天壳把解析出的语言套到整棵树上");
  assert(chat.includes("t(bannerModel.textKey, bannerModel.params)"), "断线提示条按当前语言 resolve 词条");

  // formatRelativeTime(date, now, locale)：纯函数，zh / en 每个分支都过一遍。
  const now = new Date(2026, 9, 31, 12, 0, 0).getTime(); // 本地时区，避开时区把「昨天」挪走
  const ago = (minutes) => now - minutes * 60_000;
  const rel = (minutes, locale) => mod.formatRelativeTime(ago(minutes), now, locale);
  assert(rel(0.1, "zh") === "刚刚" && rel(0.1, "en") === "Just now", "刚刚分支 zh / en");
  assert(rel(5, "zh") === "5 分钟前" && rel(5, "en") === "5 min ago", "分钟分支 zh / en");
  assert(rel(180, "zh") === "3 小时前" && rel(180, "en") === "3 h ago", "小时分支 zh / en");
  assert(rel(26 * 60, "zh") === "昨天" && rel(26 * 60, "en") === "Yesterday", "昨天分支 zh / en");
  assert(rel(3 * 24 * 60, "zh") === "3 天前" && rel(3 * 24 * 60, "en") === "3 days ago", "天数分支 zh / en");
  assert(rel(30 * 24 * 60, "zh") !== rel(30 * 24 * 60, "en"), "超过一周给日期，且按语言格式化");
  assert(mod.formatRelativeTime(undefined, now, "zh") === "", "时间戳缺失时给空串，占位交给调用方");

  // 日期 / 数字走 Intl：同一时刻在 zh / en 下格式不同，不是自己拼的字符串。
  assert(/[年月]/.test(mod.formatMonthDay(now, "zh")), `zh 月日走 Intl：${mod.formatMonthDay(now, "zh")}`);
  assert(/[A-Za-z]/.test(mod.formatMonthDay(now, "en")), `en 月日走 Intl：${mod.formatMonthDay(now, "en")}`);
  assert(mod.formatDayLabel(now, now, "zh").startsWith("今天"), "当天的分隔条带「今天」");
  assert(mod.formatNumber(1234567, "en") === "1,234,567", `en 千分位：${mod.formatNumber(1234567, "en")}`);
  assert(mod.formatNumber(1234567, "ru") !== mod.formatNumber(1234567, "en"), "不同语言的分组符号不同（ru 用不换行空格）");
  assert(chat.includes('t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })'), "已选条数走 Intl 数字格式");

  // ── 9. 硬编码扫描：src/host/*.tsx 里不该有没走词条的可见中文 ──────
  // 扫描器跳过注释（含 JSX 注释里的中文）；白名单是「承认的例外」，每条都写清理由。
  const WHITELIST = [
    {
      file: "SessionDrawer.tsx",
      line: '<span className="host-session-preview">{previews[session.id] ?? "　"}</span>',
      reason: "会话预览为空时的全角空格占位：只为撑住行高，不进读屏文案",
    },
    {
      file: "SettingsValuePage.tsx",
      line: "＋",
      reason: "数值调节按钮上的全角加号字形：符号本身不翻译，与减号成对",
    },
  ];
  const CJK = /[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]/;
  /** 逐字符走一遍，只在「代码与字符串」里认中文：注释（含 JSX 注释）里的中文不算。 */
  const cjkHits = (source) => {
    const hits = [];
    const record = (index, lineNo) => {
      const start = source.lastIndexOf("\n", index) + 1;
      const end = source.indexOf("\n", index);
      hits.push({ lineNo, line: source.slice(start, end === -1 ? source.length : end).trim() });
    };
    let state = "code";
    let quote = "";
    let lineNo = 1;
    for (let index = 0; index < source.length; index += 1) {
      const ch = source[index];
      const next = source[index + 1];
      if (ch === "\n") lineNo += 1;
      if (state === "line") {
        if (ch === "\n") state = "code";
        continue;
      }
      if (state === "block") {
        if (ch === "*" && next === "/") {
          state = "code";
          index += 1;
        }
        continue;
      }
      if (state === "string") {
        if (ch === "\\") {
          index += 1;
          continue;
        }
        if (ch === quote) {
          state = "code";
          continue;
        }
        if (CJK.test(ch)) record(index, lineNo);
        continue;
      }
      if (ch === "/" && next === "/") {
        state = "line";
        index += 1;
        continue;
      }
      if (ch === "/" && next === "*") {
        state = "block";
        index += 1;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === "`") {
        state = "string";
        quote = ch;
        continue;
      }
      if (CJK.test(ch)) record(index, lineNo);
    }
    return hits;
  };
  const allowed = new Set();
  const leftovers = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx")).sort()) {
    for (const hit of cjkHits(readFileSync(join(hostDir, file), "utf8"))) {
      const match = WHITELIST.findIndex((entry) => entry.file === file && entry.line === hit.line);
      if (match >= 0) {
        allowed.add(match);
        continue;
      }
      leftovers.push(`${file}:${hit.lineNo}`);
    }
  }
  assert(leftovers.length === 0, `host/*.tsx 里没有未走词条的可见中文${leftovers.length === 0 ? "" : `（${leftovers.join(" / ")}）`}`);
  assert(allowed.size === WHITELIST.length, `白名单没有过期条目（命中 ${allowed.size}/${WHITELIST.length}）`);
  for (const entry of WHITELIST) assert(entry.reason.length > 0, `白名单条目写明了理由：${entry.file}`);

  if (failures > 0) throw new Error(`质量断言未通过（${failures} 条）`);
  console.log("\n质量断言全部通过（断线三态 / 骨架延迟 / 区块边界 / 占位页 / 无障碍 / 对比度 / 语言与词条）。");
} finally {
  await rm(TEMP, { force: true });
}
