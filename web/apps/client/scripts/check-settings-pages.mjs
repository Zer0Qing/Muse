/**
 * 设置子页的渲染断言：归页、控件映射、枚举 label、只读行、写入接线、失败回滚、缺能力位提示。
 *
 * 覆盖五个真实子页（外观 / 聊天 / 语音 / 记忆 / 通用）。断言只用 Host 契约里的字段：
 * `values[].{key,type,value,options[].label}` + `capabilities.settings_values`，
 * 界面文案（枚举项、只读值）全部来自 fixture 里的 PROTO-* 串——只要前端偷偷自建
 * 中文表，这些断言就会因为找不到字段而失败。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-pages-check.mjs");

const ENTRY = `
export { SettingsValuePage } from "./src/host/SettingsValuePage.tsx";
export {
  SETTINGS_PAGE_SPECS,
  isReadOnlyKey,
  normalizeSettingKey,
  numberStepFor,
  pageForEntry,
  pageForValueKey,
  stepNumber,
  valuesForPage,
} from "./src/host/settingsPages.ts";
export { applySettingValue, settingValueOf } from "./src/host/settingsState.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-pages-entry.tsx", loader: "tsx" },
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

const CAPS_ON = { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true };
const CAPS_OFF = { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: false };

/** 一行 = 一个 ListRow；interactive 的行是 <button>，非 interactive 的是 <div>。 */
const countRows = (html) => (html.match(/class="muse-listrow(?:"| )/g) ?? []).length;
const countSwitches = (html) => (html.match(/role="switch"/g) ?? []).length;

const option = (value, label) => ({ value, label });

/** 覆盖五页的假数据；键名按 App 的命名习惯，label / value 用 PROTO-* 保证只可能来自协议。 */
const VALUES = [
  {
    key: "appearance.themeMode",
    type: "enum",
    value: "dark",
    options: [option("light", "PROTO-浅色"), option("dark", "PROTO-深色"), option("system", "PROTO-跟随系统")],
  },
  { key: "appearance.fontSizeScale", type: "number", value: 1 },
  {
    key: "chatPreferences.enterToSend",
    type: "enum",
    value: "enter",
    options: [option("enter", "PROTO-回车发送"), option("button", "PROTO-按钮发送")],
  },
  { key: "chat.showToolCalls", type: "boolean", value: true },
  { key: "chat.showTimestamps", type: "boolean", value: false },
  { key: "chatPreferences.streamResponse", type: "boolean", value: true },
  {
    key: "chat.thinkingLevel",
    type: "enum",
    value: "medium",
    options: [option("low", "PROTO-低"), option("medium", "PROTO-中"), option("high", "PROTO-高")],
  },
  {
    key: "tts.engine",
    type: "enum",
    value: "system",
    options: [option("system", "PROTO-系统TTS"), option("openai", "PROTO-OpenAI")],
  },
  { key: "tts.speechRate", type: "number", value: 1.1 },
  { key: "tts.voiceName", type: "text", value: "PROTO-音色名" },
  { key: "memoryEnabled", type: "boolean", value: true },
  { key: "memoryAutoSave", type: "boolean", value: false },
  {
    key: "language",
    type: "enum",
    value: "zh",
    options: [option("zh", "PROTO-中文"), option("en", "PROTO-English")],
  },
  { key: "modelConfigured", type: "text", value: "PROTO-已配置" },
];

const SNAPSHOT = {
  groups: [
    {
      key: "settings_group_chat",
      groupTitle: "PROTO-群组",
      entries: [
        {
          id: "chat",
          titleKey: "settings_screen_chat",
          title: "PROTO-聊天",
          groupKey: "settings_group_chat",
          iconKey: "chat",
          kind: "link",
        },
      ],
    },
  ],
  toggles: [],
  display: { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: [],
  values: VALUES,
};

const pageOf = (id) => mod.SETTINGS_PAGE_SPECS.find((spec) => spec.id === id);

function renderPage(pageId, overrides = {}) {
  const page = pageOf(pageId);
  return render(
    mod.createElement(mod.SettingsValuePage, {
      page,
      title: "PROTO-标题",
      snapshot: SNAPSHOT,
      loading: false,
      error: undefined,
      capabilities: CAPS_ON,
      onBack: () => undefined,
      onRetry: () => undefined,
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
      ...overrides,
    }),
  );
}

try {
  // ── 1. 归页：目录条目 → 页面，值键 → 页面 ────────────────────────
  assert(mod.pageForEntry("appearance")?.id === "appearance", "外观条目 → 外观页");
  assert(mod.pageForEntry("chat")?.id === "chat", "聊天条目 → 聊天页");
  assert(mod.pageForEntry("tts")?.id === "voice", "TTS 条目 → 语音页");
  assert(mod.pageForEntry("memory")?.id === "memory", "记忆条目 → 记忆页");
  assert(mod.pageForEntry("general")?.id === "general", "通用条目 → 通用页");
  assert(mod.pageForEntry("provider") === undefined, "没做过的分类仍走占位页");
  assert(mod.normalizeSettingKey("chatPreferences.enterToSend") === "chatpreferencesentertosend", "键归一会去掉点号并小写");

  for (const [key, pageId] of [
    ["appearance.themeMode", "appearance"],
    ["appearance.fontSizeScale", "appearance"],
    ["chatPreferences.enterToSend", "chat"],
    ["chat.showToolCalls", "chat"],
    ["chat.showTimestamps", "chat"],
    ["chatPreferences.streamResponse", "chat"],
    ["chat.thinkingLevel", "chat"],
    ["tts.engine", "voice"],
    ["tts.speechRate", "voice"],
    ["tts.voiceName", "voice"],
    ["memoryEnabled", "memory"],
    ["memoryAutoSave", "memory"],
    ["language", "general"],
    ["modelConfigured", "general"],
  ]) {
    assert(mod.pageForValueKey(key)?.id === pageId, `值键 ${key} 归到 ${pageId} 页`);
  }

  // ── 2. 每页的行数与控件映射 ──────────────────────────────────────
  const appearance = renderPage("appearance");
  assert(countRows(appearance) === 2, `外观页 2 行（主题模式 + 字号）`);
  assert(appearance.includes("PROTO-深色"), "枚举行右侧显示 Host 下发的 label（当前值）");
  assert(!appearance.includes(">dark<"), "枚举行不把原始 value 当显示名");
  assert(/aria-label="[^"]*减小"/.test(appearance), "数字行有 − 按钮（无障碍名）");
  assert(/aria-label="[^"]*增大"/.test(appearance), "数字行有 ＋ 按钮（无障碍名）");
  assert(appearance.includes(">1<"), "数字行显示当前值");

  const chat = renderPage("chat");
  assert(countRows(chat) === 5, "聊天页 5 行（发送键 / 工具调用 / 时间戳 / 流式 / 思考强度）");
  assert(countSwitches(chat) === 3, "聊天页 3 个布尔行渲染成开关");
  assert(chat.includes("PROTO-回车发送") && chat.includes("PROTO-中"), "两个枚举行各显示自己的 Host label");

  const voice = renderPage("voice");
  assert(countRows(voice) === 3, "语音页 3 行（引擎 / 语速 / 音色名）");
  assert(voice.includes("PROTO-系统TTS"), "TTS 引擎行显示 Host label");
  assert(voice.includes(">1.1<"), "语速行显示当前数字");
  assert(voice.includes("PROTO-音色名"), "音色名行显示 Host 下发的值");

  const memory = renderPage("memory");
  assert(countRows(memory) === 2, "记忆页 2 行（开关 + 自动保存）");
  assert(countSwitches(memory) === 2, "记忆页两行都是开关");

  const general = renderPage("general");
  assert(countRows(general) === 2, "通用页 2 行（语言 + 模型是否已配置）");
  assert(general.includes("PROTO-中文"), "语言行显示 Host label");
  assert(general.includes("PROTO-已配置"), "模型是否已配置行显示 Host 下发的值");

  // ── 3. 只读行：音色名 / 模型是否已配置不给写入口 ─────────────────
  assert(mod.isReadOnlyKey("tts.voiceName") && mod.isReadOnlyKey("modelConfigured"), "只读键被识别");
  assert(!mod.isReadOnlyKey("tts.speechRate"), "可写键不会被误判成只读");
  const readOnlyRowHtml = render(
    mod.createElement(mod.SettingsValuePage, {
      page: pageOf("voice"),
      title: "PROTO-标题",
      snapshot: { ...SNAPSHOT, values: [{ key: "tts.voiceName", type: "text", value: "PROTO-音色名" }] },
      loading: false,
      error: undefined,
      capabilities: CAPS_ON,
      onBack: () => undefined,
      onRetry: () => undefined,
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
    }),
  );
  assert(!readOnlyRowHtml.includes("host-stepper") && countSwitches(readOnlyRowHtml) === 0, "只读行没有任何写控件");
  const readOnlyBooleanHtml = renderPage("general", {
    snapshot: { ...SNAPSHOT, values: [{ key: "modelConfigured", type: "boolean", value: true }] },
  });
  assert(countSwitches(readOnlyBooleanHtml) === 1 && readOnlyBooleanHtml.includes("disabled"), "只读键即使下发成布尔，也只给禁用开关");

  // ── 4. 枚举行：label 缺失时回退原始 value ───────────────────────
  const fallbackHtml = renderPage("chat", {
    snapshot: {
      ...SNAPSHOT,
      values: [
        {
          key: "chat.thinkingLevel",
          type: "enum",
          value: "experimental",
          options: [option("low", "PROTO-低")],
        },
      ],
    },
  });
  assert(fallbackHtml.includes(">experimental<"), "枚举当前值不在 options 里时直接显示原始 value");

  // ── 5. 写入接线：每种控件都调用 onWrite（源码级） ────────────────
  const pageSource = readFileSync(join(ROOT, "src", "host", "SettingsValuePage.tsx"), "utf8");
  assert(/onWrite\(value\.key, next\)/.test(pageSource), "开关写入走 onWrite(key, boolean)");
  assert(/onWrite\(value\.key, stepNumber\(current, -1\)\)/.test(pageSource), "数字 − 写入走 onWrite(key, number)");
  assert(/onWrite\(value\.key, stepNumber\(current, 1\)\)/.test(pageSource), "数字 ＋ 写入走 onWrite(key, number)");
  assert(/onWrite\(value\.key, option\.value\)/.test(pageSource), "枚举选项写入走 onWrite(key, optionValue)");
  const hostSettingsSource = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(/onWrite=\{onWrite\}/.test(hostSettingsSource), "主页把 onWrite 透传给子页");
  assert(
    /pageForEntry\(openPage\.entry\.id\)/.test(hostSettingsSource) &&
      /<SettingsValuePage/.test(hostSettingsSource) &&
      /<HostPlaceholder/.test(hostSettingsSource),
    "点开已做过的分类进真实子页，其余分类走统一占位页",
  );
  const hostChatSource = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  assert(/onWrite=\{updateSettingValue\}/.test(hostChatSource), "聊天壳把 useHostConnection 的写入接到设置页");
  const connectionSource = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
  assert(/type: "settings\.update", key, value/.test(connectionSource), "写入下发的是 settings.update 命令");
  assert(/typeof value === "boolean" \? \(value \? "true" : "false"\) : value/.test(connectionSource), "布尔按 Host 现有的 true / false 字面量下发");
  assert(
    /applySettingValue\(currentSnapshot, rejected\.key, rejected\.previous\)/.test(connectionSource),
    "Host 回 error 时用写入前的值回滚",
  );
  assert(
    /optionsRef\.current\.onError\(describeHostError\(event\.error, tt\("error\.settingsSave"\), tt\)\)/.test(connectionSource),
    "回滚后把 Host 给的原因交给 toast（不吞失败原因）",
  );

  // ── 6. 乐观更新与失败回滚（纯函数） ─────────────────────────────
  const before = mod.settingValueOf(SNAPSHOT, "chat.showToolCalls");
  assert(before === true, "写入前读到的是 values 里的原值");
  const optimistic = mod.applySettingValue(SNAPSHOT, "chat.showToolCalls", false);
  assert(mod.settingValueOf(optimistic, "chat.showToolCalls") === false, "乐观更新立刻改掉 values 里的值");
  assert(SNAPSHOT.values.find((v) => v.key === "chat.showToolCalls").value === true, "乐观更新不修改原对象（纯函数）");
  const rolledBack = mod.applySettingValue(optimistic, "chat.showToolCalls", before);
  assert(mod.settingValueOf(rolledBack, "chat.showToolCalls") === true, "用写入前的值再 apply 一次即完成回滚");
  const numbers = mod.applySettingValue(SNAPSHOT, "tts.speechRate", 1.2);
  assert(mod.settingValueOf(numbers, "tts.speechRate") === 1.2, "数字写入同样只改目标键");
  const togglesProjection = mod.applySettingValue(
    {
      ...SNAPSHOT,
      toggles: [{ key: "piiGuardEnabled", enabled: true }],
    },
    "piiGuardEnabled",
    false,
  );
  assert(togglesProjection.toggles[0].enabled === false, "布尔写入同时改到 toggles 投影（老主页也跟着变）");
  const legacySnapshot = { ...SNAPSHOT, values: undefined };
  assert(mod.applySettingValue(legacySnapshot, "chat.showToolCalls", false).values === undefined, "没有 values 的旧快照不会凭空长出 values 字段");

  // 数字步进：整数按 1，小数按 0.1，不越过 0
  assert(mod.numberStepFor(1) === 1 && mod.numberStepFor(1.1) === 0.1, "整数与小数步长不同");
  assert(mod.stepNumber(1, 1) === 2 && mod.stepNumber(1.1, 1) === 1.2, "＋ 按步长前进且不留浮点尾巴");
  assert(mod.stepNumber(0, -1) === 0, "− 不越过 0");

  // ── 7. 能力位 / 加载态 / 空态 ───────────────────────────────────
  const unsupported = renderPage("appearance", { capabilities: CAPS_OFF });
  assert(unsupported.includes("当前版本暂不支持在网页端调整"), "缺 settings_values 能力位时给明确提示");
  assert(countRows(unsupported) === 1 && countSwitches(unsupported) === 0, "缺能力位时不渲染任何可调行");

  const oldHost = renderPage("appearance", { snapshot: { ...SNAPSHOT, values: undefined } });
  assert(oldHost.includes("当前版本暂不支持在网页端调整"), "Host 不下发 values 时同样给提示（而不是空列表）");

  const loading = renderPage("appearance", { snapshot: undefined, loading: true });
  // 骨架延迟 300ms（useDelayedFlag）：刚进子页时只给安静占位
  assert(loading.includes('role="status"') && !loading.includes("muse-skeleton"), "values 还没到时先给安静占位（骨架延迟 300ms）");

  const failed = renderPage("appearance", { snapshot: undefined, loading: false, error: "PROTO-读取失败" });
  assert(failed.includes("PROTO-读取失败") && failed.includes(">重试<"), "读取失败显示可重试错误条");

  const emptyPage = renderPage("memory", { snapshot: { ...SNAPSHOT, values: [] } });
  assert(emptyPage.includes("这一页暂无可调项"), "本页没有匹配的值时说明原因，不给空白");

  // ── 8. 样式：新类名只走令牌 ────────────────────────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-setting-value", "host-stepper", "host-stepper-button", "host-stepper-value", "host-setting-check"]) {
    assert(css.includes(`.${selector}`), `样式里定义了 .${selector}`);
  }

  if (failures > 0) throw new Error(`设置子页断言未通过（${failures} 条）`);
  console.log("\n设置子页断言全部通过（归页 / 控件 / 只读 / 写入 / 回滚 / 能力位）。");
} finally {
  await rm(TEMP, { force: true });
}
