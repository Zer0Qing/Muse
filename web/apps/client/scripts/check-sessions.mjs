/**
 * 会话与助手可用性的断言：助手选择弹层、行内重命名校验、归档可见性、删除文案、
 * 滚动位置记忆、focus_not_held 的专属提示。
 *
 * 纯逻辑（sessionActions / scrollMemory / hostErrors）直接跑；列表与弹层用
 * renderToStaticMarkup 渲染；交互路径（点开重命名、点助手）按源码接线断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".sessions-check.mjs");

const ENTRY = `
export {
  assistantIdForNewSession,
  isRenameValid,
  normalizeSessionTitle,
  shouldAskAssistant,
  visibleSessions,
} from "./src/host/sessionActions.ts";
export { ZH } from "./src/i18n/messages.ts";
export { ScrollMemory } from "./src/host/scrollMemory.ts";
export { describeHostError } from "./src/host/hostErrors.ts";
export { translate } from "./src/i18n/translate.ts";
export { HostAvatar } from "./src/host/HostAvatar.tsx";
export { AssistantPicker } from "./src/host/AssistantPicker.tsx";
export { SessionSidebar } from "./src/host/SessionDrawer.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "sessions-entry.tsx", loader: "tsx" },
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

const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const DRAWER = readFileSync(join(ROOT, "src", "host", "SessionDrawer.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");

const session = (id, title, overrides = {}) => ({ id, title, assistantId: "a1", updatedAt: 1_700_000_000_000, archived: false, pinned: false, ...overrides });

try {
  // ── 1. 新建会话：多助手先选，单助手直接建 ──────────────────────
  const two = [{ id: "a1", name: "Muse" }, { id: "a2", name: "Iris" }];
  const one = [{ id: "a1", name: "Muse" }];
  assert(mod.shouldAskAssistant(two) === true, "助手多于一个时要先选");
  assert(mod.shouldAskAssistant(one) === false, "只有一个助手时不弹选择");
  assert(mod.shouldAskAssistant([]) === false, "助手列表还没到时直接建（交给 Host 的默认助手）");
  assert(mod.assistantIdForNewSession(one) === "a1", "只有一个助手时把它带上");
  assert(mod.assistantIdForNewSession(two) === undefined, "多个助手时不替用户决定");
  assert(/shouldAskAssistant\(assistants\)/.test(CHAT), "新建会话走「要不要先选助手」的判断");
  assert(/assistantIdForNewSession\(assistants\)/.test(CHAT), "单助手路径带上 assistantId");
  assert(/type: "session\.new", assistantId/.test(CHAT), "选定助手后 session.new 带 assistantId");

  const pickerHtml = render(
    mod.createElement(mod.AssistantPicker, {
      assistants: [
        { id: "a1", name: "PROTO-Muse", avatarUri: "/api/media/face-1", isDefault: true },
        { id: "a2", name: "PROTO-Iris" },
      ],
      onPick: () => undefined,
      onClose: () => undefined,
    }),
  );
  assert(pickerHtml.includes('role="dialog"') && pickerHtml.includes('aria-label="选择助手"'), "助手选择是对话框");
  assert(pickerHtml.includes("PROTO-Muse") && pickerHtml.includes("PROTO-Iris"), "两行助手都列出名字");
  assert(pickerHtml.includes(">默认<"), "默认助手有标记");
  assert(pickerHtml.includes('src="/api/media/face-1"'), "有可用头像地址时渲染图片");
  assert((pickerHtml.match(/role="option"/g) ?? []).length === 2, "每个助手是一个 option");
  assert(pickerHtml.includes("取消"), "弹层有取消入口");

  const avatarFallback = render(mod.createElement(mod.HostAvatar, { name: "Muse" }));
  assert(avatarFallback.includes("host-avatar-initial") && avatarFallback.includes(">M<"), "没有头像时用名字首字");
  const avatarLocal = render(mod.createElement(mod.HostAvatar, { name: "Muse", uri: "file:///data/face.png" }));
  assert(avatarLocal.includes(">M<") && !avatarLocal.includes("<img"), "手机本地头像地址退回首字，不丢碎图");

  // ── 2. 行内重命名：空名不允许 ─────────────────────────────────
  assert(mod.isRenameValid("") === false, "空名不允许提交");
  assert(mod.isRenameValid("   ") === false, "只有空白也算空名");
  assert(mod.isRenameValid(" 新名字 ") === true, "正常标题可以提交");
  assert(mod.normalizeSessionTitle("  新   名字 ") === "新 名字", "提交前去掉首尾空白并压缩中间空白");
  assert(DRAWER.includes("host-session-rename") && /disabled=\{!isRenameValid\(renameDraft\)\}/.test(DRAWER), "空名时「保存」被禁用");
  assert(DRAWER.includes('t("sessions.renameEmpty")'), "空名给一句提示");
  assert(/event\.key === "Enter"/.test(DRAWER) && /event\.key === "Escape"/.test(DRAWER), "重命名支持 Enter 提交 / Esc 取消");
  assert(/setRenamingId\(session\.id\)/.test(DRAWER), "⋯ 菜单里的「重命名」切到行内输入");
  assert(!/window\.prompt\(/.test(DRAWER), "不再用 window.prompt 改名");

  // ── 3. 归档可见性开关 ─────────────────────────────────────────
  const mixed = [session("s1", "进行中"), session("s2", "已归档的", { archived: true })];
  assert(mod.visibleSessions(mixed, false).length === 1, "默认不列归档会话");
  assert(mod.visibleSessions(mixed, true).length === 2, "打开「显示归档」后列出来");
  // Host 以后会把归档会话也放进快照：过滤必须留在面板这一层，不能在上层提前艁掉
  assert(/visibleSessions\(sessions, showArchived\)/.test(DRAWER), "归档过滤在会话面板里按开关算");
  assert(/sessions=\{sessions\}/.test(CHAT), "聊天壳把快照里的会话原样交给面板（含归档）");

  const sidebar = (showArchived) =>
    render(
      mod.createElement(mod.SessionSidebar, {
        sessions: mixed,
        ready: true,
        showArchived,
        onToggleArchived: () => undefined,
        currentId: "s1",
        previews: {},
        buildId: "test",
        onSelect: () => undefined,
        onNew: () => undefined,
        onRequestPreviews: () => undefined,
        onRename: () => undefined,
        onArchive: () => undefined,
        onDelete: () => undefined,
        onOpenSettings: () => undefined,
        onLogout: () => undefined,
        collapsed: false,
        onToggleSidebar: () => undefined,
      }),
    );
  const hidden = sidebar(false);
  assert(!hidden.includes("已归档的"), "开关关闭时归档会话不出现");
  assert(hidden.includes("显示归档") && hidden.includes('role="switch"'), "头部有「显示归档」开关");
  const shown = sidebar(true);
  assert(shown.includes("已归档的") && shown.includes("已归档"), "开关打开后出现归档会话并标注「已归档」");
  assert(/onToggleArchived/.test(DRAWER), "开关状态由上层持有");

  // ── 4. 删除确认文案（词条在 i18n 表里，聊天壳负责套参数）────────
  assert(mod.ZH["sessions.deleteConfirm"].includes("{name}"), "会话删除确认是带占位符的词条");
  assert(mod.ZH["sessions.deleteConfirm"].includes("全部消息") && mod.ZH["sessions.deleteConfirm"].includes("无法恢复"), "确认词条说明会删掉全部消息且不可恢复");
  assert(CHAT.includes('t("sessions.deleteConfirm", { name: title.trim() || t("sessions.untitled") })'), "聊天壳把会话名与未命名兑底套进词条");
  assert(!CHAT.includes("此操作不可撤销"), "旧的死文案已清除");

  // ── 5. 滚动位置记忆 ───────────────────────────────────────────
  const memory = new mod.ScrollMemory();
  assert(memory.recall("s1") === undefined, "没记录过就没有位置");
  memory.remember("s1", 420);
  memory.remember("s2", 0);
  assert(memory.recall("s1") === 420 && memory.recall("s2") === 0, "每个会话各记各的");
  memory.remember("s1", 900);
  assert(memory.recall("s1") === 900, "再次记录会覆盖旧值");
  memory.forget("s1");
  assert(memory.recall("s1") === undefined, "忘记后不再返回位置");
  memory.remember("bad", -5);
  assert(memory.recall("bad") === undefined, "非法位置不记录");
  const capped = new mod.ScrollMemory();
  for (let index = 0; index < 260; index += 1) capped.remember(`s${index}`, index);
  assert(capped.size() === 200, "记忆条数有上限，不会无限增长");
  assert(/scrollMemoryRef\.current\.remember|scrollMemoryRef\.current\.recall/.test(CHAT), "聊天页用这份记忆存取位置");
  assert(/pendingRestoreRef/.test(CHAT), "切会话后等内容换完再落一次位置");

  // ── 6. focus_not_held 的专属提示 ─────────────────────────────
  const zh = (key) => mod.translate("zh", key);
  assert(mod.describeHostError("focus_not_held", "x", zh) === zh("error.focusNotHeld"), "focus_not_held 换成专属提示");
  assert(zh("error.focusNotHeld").includes("其他设备"), `中文提示：${zh("error.focusNotHeld")}`);
  assert(mod.translate("en", "error.focusNotHeld").includes("Another device"), "英文提示也有专属文案");
  assert(mod.describeHostError("session_not_found", "x", zh).includes("已经不存在"), "session_not_found 也有可读文案");
  const notInSession = mod.describeHostError("message_not_in_current_session", "x", zh);
  assert(notInSession.includes("不在当前会话"), `message_not_in_current_session 的人话：${notInSession}`);
  const deletedRace = mod.describeHostError("message_not_found", "x", zh);
  assert(deletedRace.includes("刚被删除") || deletedRace.includes("已经不存在"), `message_not_found（删除竞态）的人话：${deletedRace}`);
  assert(mod.describeHostError("某个不太熟的错误", "兜底", zh) === "某个不太熟的错误", "不认识的原因原样透出");
  assert(mod.describeHostError(undefined, "兜底", zh) === "兜底", "没有原因时用兜底文案");
  assert((CONNECTION.match(/describeHostError\(/g) ?? []).length >= 3, "错误事件三条路径都过一遍翻译");

  // ── 7. 顶栏助手信息 ───────────────────────────────────────────
  assert(CHAT.includes("currentAssistant") && CHAT.includes("<HostAvatar name={currentAssistant.name}"), "顶栏渲染当前助手的头像");
  assert(CHAT.includes('t("chat.pickAssistant")'), "没有会话时顶栏给引导语");
  assert(CHAT.includes("${currentAssistant.name} · "), "顶栏副标题带助手名");

  // ── 8. 样式 ───────────────────────────────────────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-avatar", "host-picker", "host-session-rename", "host-archived-toggle", "host-switch-loading", "host-topbar-head"]) {
    assert(css.includes(`.${selector}`), `样式里定义了 .${selector}`);
  }

  if (failures > 0) throw new Error(`会话与助手断言未通过（${failures} 条）`);
  console.log("\n会话与助手断言全部通过（助手选择 / 重命名 / 归档 / 删除文案 / 滚动记忆 / 错误提示）。");
} finally {
  await rm(TEMP, { force: true });
}
