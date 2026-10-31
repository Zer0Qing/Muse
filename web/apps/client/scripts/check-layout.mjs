/**
 * 宽屏适配的静态断言：三档断点、左栏与抽屉的互斥、内容最大宽约束、快捷键。
 *
 * 这些是纯 CSS / 结构约束，没有浏览器就没法在运行时断言，所以直接对 `styles.css`
 * 与组件源码做结构化检查：先把 `@media (min-width: …)` 的块体裁出来，再断言块里
 * 到底写了什么。这样「桌面档藏掉抽屉」「手机档不显示左栏」这类互斥关系一旦被误改，
 * `npm test` 立刻失败。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const CSS_RAW = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const DRAWER = readFileSync(join(ROOT, "src", "host", "SessionDrawer.tsx"), "utf8");
const LIGHTBOX = readFileSync(join(ROOT, "src", "host", "ImageLightbox.tsx"), "utf8");

/** 注释里也会出现选择器名，先剥掉注释再找规则体，避免命中注释。 */
const CSS = CSS_RAW.replace(/\/\*[\s\S]*?\*\//g, "");

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

/** 裁出某个 `@media (…)` 的块体（含花括号内的全部内容）。 */
function mediaBlock(source, query) {
  const marker = `@media (${query}) {`;
  const start = source.indexOf(marker);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    else if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  return "";
}

/** 某个选择器紧随其后的声明体（取到下一个 `}`）。 */
function ruleBody(block, selector) {
  const start = block.indexOf(selector);
  if (start < 0) return "";
  const open = block.indexOf("{", start);
  const close = block.indexOf("}", open);
  if (open < 0 || close < 0) return "";
  return block.slice(open + 1, close);
}

const tablet = mediaBlock(CSS, "min-width: 600px");
const desktop = mediaBlock(CSS, "min-width: 1024px");

try {
  // ── 1. 三档断点 ─────────────────────────────────────────────────
  assert(CSS.includes("@media (min-width: 600px) {"), "有平板档断点（min-width: 600px）");
  assert(CSS.includes("@media (min-width: 1024px) {"), "有桌面档断点（min-width: 1024px）");
  assert(tablet.length > 0 && desktop.length > 0, "两个断点都带块体（不是空壳）");
  assert(
    !/min-width:\s*(7\d\d|8\d\d|9\d\d)px/.test(CSS),
    "没有插进三档以外的宽度断点（手机档就是默认样式，不加查询）",
  );

  // ── 2. 左栏与抽屉互斥：手机 / 平板只有抽屉，桌面只有左栏 ──────────
  assert(/display:\s*none/.test(ruleBody(CSS, ".host-sidebar {")), "手机 / 平板档左栏默认不显示");
  const sidebarDesktop = ruleBody(desktop, ".host-sidebar {");
  assert(/display:\s*flex/.test(sidebarDesktop), "桌面档左栏常驻（display: flex）");
  assert(/flex-direction:\s*column/.test(sidebarDesktop), "左栏是竖向列（会话列表自己滚动）");
  assert(
    /flex:\s*0 0 var\(--host-sidebar-w\)/.test(sidebarDesktop) && /width:\s*var\(--host-sidebar-w\)/.test(sidebarDesktop),
    "左栏宽度走 --host-sidebar-w（280–320 档位）",
  );
  assert(/\.host-scrim,\s*\.host-drawer\s*\{\s*display:\s*none/.test(desktop), "桌面档抽屉与遮罩都不出现（与左栏互斥）");
  assert(/button\.quiet\.host-menu-button\s*\{\s*display:\s*none/.test(desktop), "桌面档顶栏不显示 ☰");
  // 这两个「隐藏」必须比 `button.host-icon-button { display: inline-grid }` 更具体，否则同级类名会被盖掉
  assert(/button\.host-icon-button\s*\{[^}]*display:\s*inline-grid/.test(CSS), "图标按钮基类确实自带了 display（所以隐藏规则要带 .quiet 才能盖过）");

  // ── 3. 内容最大宽与留白 ─────────────────────────────────────────
  const threadTablet = ruleBody(tablet, ".host-thread");
  assert(/max-width:\s*var\(--host-content-max\)/.test(threadTablet), "平板档消息栏限宽（var(--host-content-max)）");
  assert(/margin-inline:\s*auto/.test(threadTablet), "平板档消息栏居中、两侧留白");
  assert(/--host-content-max:\s*640px/.test(ruleBody(CSS, ".host-scope {")), "平板档内容最大宽默认 640（定义在 .host-scope）");
  assert(/--host-content-max:\s*720px/.test(desktop), "桌面档内容最大宽切到 720");
  assert(
    /\.host-composer\s*\{[^}]*padding-inline:\s*max\([^}]*var\(--host-content-max\)/.test(tablet),
    "平板档输入栏跟着内容宽居中（不是整屏宽度）",
  );
  assert(
    /\.host-composer\s*\{[^}]*padding-inline:\s*max\(var\(--muse-space-large-gap\)/.test(desktop),
    "桌面档输入栏左右内边距至少 24（largeGap）",
  );
  assert(
    /\.host-scroll\s*\{\s*padding-inline:\s*var\(--muse-space-large-gap\)/.test(desktop),
    "桌面档消息区左右内边距至少 24",
  );
  assert(
    /\.host-root:not\(\.is-settings\)\s*\{\s*flex-direction:\s*row/.test(desktop),
    "桌面档壳改成左右两栏（仅聊天视图，设置页仍单栏）",
  );
  assert(
    /\.host-root\.is-settings \.muse-screen-body\s*\{[^}]*max-width:\s*var\(--host-content-max\)/.test(desktop),
    "设置页在桌面档限宽居中，不拉满整屏",
  );
  assert(/--host-sidebar-w:\s*300px/.test(ruleBody(CSS, ".host-scope {")), "左栏宽度变量取 300（280–320 中值）");
  // 左栏可折叠：默认收起态不占位，收起后顶栏出展开按钮
  assert(/\.host-sidebar\.is-collapsed\s*\{\s*display:\s*none/.test(desktop), "桌面档左栏收起后不占位");
  assert(/\.host-root\.is-sidebar-collapsed \.host-sidebar-toggle\s*\{\s*display:\s*inline-grid/.test(desktop), "收起后顶栏出现展开按钮");
  assert(
    /display:\s*none/.test(ruleBody(CSS, "button.quiet.host-sidebar-toggle {")),
    "展开按钮默认不显示（手机 / 平板档不出现）",
  );

  // ── 4. 结构：左栏常驻 + 主列包住顶栏 / 消息 / 输入栏 ──────────────
  assert(/<SessionSidebar/.test(CHAT), "聊天页渲染常驻左栏");
  assert(/<div className="host-main">/.test(CHAT), "顶栏 / 消息区 / 输入栏被包进主列");
  assert(/host-menu-button/.test(CHAT), "☰ 按钮带可被桌面档隐藏的类名");
  assert(/className="host-root host-scope is-settings"/.test(CHAT), "设置视图带 is-settings 标记");
  assert(/export function SessionSidebar/.test(DRAWER), "左栏是独立导出的组件（与抽屉共用同一面板）");
  assert(/host-sidebar-settings[\s\S]{0,160}t\("common\.settings"\)/.test(DRAWER), "左栏顶部有设置入口");
  assert(/variant === "drawer"[\s\S]{0,300}host-drawer-close/.test(DRAWER), "抽屉保留关闭键（手机档）");
  assert(/<SessionDrawer/.test(CHAT), "手机 / 平板档仍挂抽屉（由 CSS 在桌面档隐藏）");
  assert(/\(min-width: 1024px\)/.test(DRAWER), "左栏预取用的断点与 CSS 桌面档一致（1024）");
  assert(/host-sidebar-collapse/.test(DRAWER), "左栏头部有收起键");
  assert(/collapsed=\{sidebarCollapsed\}/.test(CHAT) && /is-sidebar-collapsed/.test(CHAT), "左栏折叠状态由聊天页持有");

  // ── 5. 会话行沿 ListRow 几何 + 选中态 ───────────────────────────
  const sessionRow = ruleBody(CSS, "button.host-session {");
  assert(/min-height:\s*var\(--muse-component-list-row-min-height\)/.test(sessionRow), "会话行高度走 ListRow 的 60");
  assert(
    /padding:\s*var\(--muse-component-list-row-padding-y\) var\(--muse-component-list-row-padding-x\)/.test(sessionRow),
    "会话行内边距走 ListRow 的 16/12",
  );
  assert(/\.host-session-wrap\.is-active/.test(CSS), "选中会话有高亮态");

  // ── 6. 快捷键：Enter 发送 / Shift+Enter 换行 / Esc 关全屏 ────────
  assert(/if \(event\.key !== "Enter"\) return;/.test(CHAT) && /if \(event\.shiftKey\) return;/.test(CHAT), "Enter 发送、Shift+Enter 不拦截（换行）");
  assert(/event\.nativeEvent\.isComposing/.test(CHAT), "输入法组字中不误发");
  assert(/event\.key === "Escape"/.test(LIGHTBOX), "Esc 关闭全屏图片");

  if (failures > 0) throw new Error(`宽屏布局断言未通过（${failures} 条）`);
  console.log("\n宽屏布局断言全部通过（三档断点 / 左栏与抽屉互斥 / 内容限宽 / 快捷键）。");
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
