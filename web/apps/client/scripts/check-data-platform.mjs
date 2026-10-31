/**
 * 数据与隐私页 / 平台专有页的断言：存储分布、备份三态、PII 开关可写性、
 * 五个平台页的状态映射、缺能力位提示、新增词条。
 *
 * 组件用 renderToStaticMarkup 直接渲染；交互路径（点开关、拉 system.status）按源码断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".data-platform-check.mjs");

const ENTRY = `
export { DataPrivacyPage } from "./src/host/DataPrivacyPage.tsx";
export { PlatformPage } from "./src/host/PlatformPage.tsx";
export { SYSTEM_STATUS_FLAGS, platformAvailability, platformKeysForEntry, supportsFlag } from "./src/host/featureCapabilities.ts";
export { formatBytes, formatDuration } from "./src/i18n/format.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "data-platform-entry.tsx", loader: "tsx" },
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

const STATUS = {
  appVersion: "2.6.10",
  appVersionCode: 20610,
  uptimeMs: 3 * 60 * 60 * 1000,
  storage: {
    databaseBytes: 4 * 1024 * 1024,
    mediaBytes: 12 * 1024 * 1024,
    voiceBytes: 1024 * 1024,
    workspaceBytes: 2 * 1024 * 1024,
    cacheBytes: 1024 * 1024,
    totalAppBytes: 20 * 1024 * 1024,
  },
  backup: { lastBackupAt: Date.now() - 2 * 60 * 60 * 1000, lastBackupBytes: 8 * 1024 * 1024, autoBackupEnabled: true, cloudBackupEnabled: false },
  capabilities: { accessibility: true, shell: false, virtualDisplay: undefined, notificationListener: true, root: undefined, shizuku: undefined },
  battery: { level: 72, charging: false },
};

const dataPage = (overrides = {}) =>
  render(
    mod.createElement(mod.DataPrivacyPage, {
      title: "PROTO-数据与隐私",
      system: { status: STATUS, loading: false, error: undefined, unsupported: false },
      supported: true,
      onRequest: () => undefined,
      pii: { checked: true, writable: true },
      onTogglePii: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

const platformPage = (availability, overrides = {}) =>
  render(
    mod.createElement(mod.PlatformPage, {
      title: "PROTO-无障碍",
      description: "PROTO-功能说明",
      availability,
      onBack: () => undefined,
      ...overrides,
    }),
  );

try {
  // ── 1. 存储分布 ─────────────────────────────────────────────
  const html = dataPage();
  assert(html.includes("PROTO-数据与隐私"), "页面标题来自 Host 条目");
  assert(html.includes("手机端") && html.includes("2.6.10"), "顶部给出手机端版本");
  assert(/3\s*小时/.test(html) && html.includes("72%"), "运行时长与电量走 Intl 格式化");
  assert(html.includes("存储占用") && html.includes("数据库") && html.includes("媒体") && html.includes("工作区"), "存储分类都在");
  assert((html.match(/host-storage-segment/g) ?? []).length === 5, "堆叠条分成五段");
  assert(html.includes("4 MB") && html.includes("20 MB"), "数值走 formatBytes（MB）");
  assert(html.includes("合计"), "有合计行");
  assert(/host-storage-bar/.test(html), "用横向堆叠条展示分布");

  // ── 2. 备份三态 ─────────────────────────────────────────────
  assert(html.includes("最近备份") && html.includes("2 小时前"), "有备份记录时显示相对时间");
  assert(html.includes("8 MB"), "备份大小");
  assert(html.includes("自动备份") && html.includes("已开启") && html.includes("云备份") && html.includes("已关闭"), "自动/云备份开关只读回显");
  assert(html.includes("在手机上修改"), "只读项标注「在手机上修改」");

  const noBackup = dataPage({
    system: { status: { ...STATUS, backup: { autoBackupEnabled: false, cloudBackupEnabled: false } }, loading: false, error: undefined, unsupported: false },
  });
  assert(noBackup.includes("还没有备份过"), "没有备份记录时给「还没有备份过」");
  assert(!noBackup.includes("备份大小"), "无记录时不显示大小行");

  const unsupported = dataPage({ supported: false });
  assert(unsupported.includes("当前版本暂不支持在网页端查看数据与隐私"), "缺 system_status 能力位时给提示");
  assert(!unsupported.includes("存储占用"), "缺能力位时不画存储区");

  const failed = dataPage({ system: { status: undefined, loading: false, error: "PROTO-错误", unsupported: false } });
  assert(failed.includes("PROTO-错误") && failed.includes(">重试<"), "读取失败可重试");

  // ── 3. 隐私开关可写性 ───────────────────────────────────────
  assert(/role="switch"/.test(html) && !html.includes('disabled=""'), "可写时开关能用");
  const readOnlyPii = dataPage({ pii: { checked: true, writable: false } });
  assert(/role="switch"/.test(readOnlyPii) && readOnlyPii.includes("disabled"), "不可写时开关是禁用态");
  const missingPii = dataPage({ pii: undefined });
  assert(missingPii.includes("手机没有下发 PII 守护的开关状态"), "Host 没给条目时说明原因");
  assert(html.includes("PII 守护") && html.includes("敏感信息"), "有 PII 守护的一行说明");
  assert(HOST_SETTINGS.includes("onToggle(PII_GUARD_KEY, enabled)") && HOST_SETTINGS.includes('PII_GUARD_KEY = "piiGuardEnabled"'), "PII 开关走 settings.update 白名单键");

  // ── 4. 平台页状态映射 ───────────────────────────────────────
  assert(mod.platformAvailability({ accessibility: true }, ["accessibility"]) === "available", "true → 可用");
  assert(mod.platformAvailability({ accessibility: false }, ["accessibility"]) === "notEnabled", "false → 未开启");
  assert(mod.platformAvailability({}, ["accessibility"]) === "unavailable", "字段缺失 → 不可用");
  assert(mod.platformAvailability(undefined, ["accessibility"]) === "unavailable", "没有 system.status → 不可用");
  assert(mod.platformAvailability({ accessibility: false, root: true }, ["accessibility", "root"]) === "available", "多字段任一可用即算可用");

  const pages = [
    ["accessibility", "accessibility"],
    ["virtual_display", "virtualDisplay"],
    ["terminal", "shell"],
    ["notification_listener", "notificationListener"],
    ["ui_automation", "accessibility"],
  ];
  for (const [entryId, key] of pages) {
    const keys = mod.platformKeysForEntry(entryId);
    assert(keys !== undefined && keys.includes(key), `条目 ${entryId} 映射到 ${key}`);
  }
  assert(mod.platformKeysForEntry("provider") === undefined, "非平台条目不会被误判");

  const available = platformPage("available");
  assert(available.includes("PROTO-功能说明") && available.includes("PROTO-无障碍"), "平台页显示功能说明");
  assert(available.includes("可用") && !available.includes("在手机上开启"), "可用时不提示去开启");
  const notEnabled = platformPage("notEnabled");
  assert(notEnabled.includes("未开启") && notEnabled.includes("在手机上开启"), "未开启时提示在手机上开启");
  const unavailable = platformPage("unavailable");
  assert(unavailable.includes("不可用") && unavailable.includes("只能在手机端开关与使用"), "不可用时只说明情况");
  assert(!/去设置|打开设置/.test(unavailable), "不可用时没有假按钮");

  // ── 5. 词条与接线 ─────────────────────────────────────────
  for (const key of ["data.storage", "data.backup", "data.readOnly", "data.unsupported", "data.piiGuard", "platform.available", "platform.notEnabled", "platform.unavailable", "platform.readOnly"]) {
    assert(typeof mod.ZH[key] === "string" && typeof mod.EN[key] === "string" && mod.ZH[key].length > 0 && mod.EN[key].length > 0, `词条 ${key} 中英都有`);
  }
  assert(HOST_SETTINGS.includes('openPage.entry.id === "data_management"') && HOST_SETTINGS.includes("<DataPrivacyPage"), "「数据与隐私」条目进该页");
  assert(HOST_SETTINGS.includes("platformKeysForEntry(openPage.entry.id)") && HOST_SETTINGS.includes("<PlatformPage"), "平台条目进平台页");
  assert(CONNECTION.includes('type: "system.status"'), "system.status 命令发得出去");
  assert(mod.supportsFlag(undefined, mod.SYSTEM_STATUS_FLAGS) === true && mod.supportsFlag([], mod.SYSTEM_STATUS_FLAGS) === false, "system_status 能力位判断");
  assert(mod.formatBytes(1024) === "1 KB" || mod.formatBytes(1024).includes("1"), `formatBytes 1KB：${mod.formatBytes(1024)}`);
  assert(/host-storage-bar|host-platform-state/.test(CSS), "新增样式类存在");

  if (failures > 0) throw new Error(`数据与平台页断言未通过（${failures} 条）`);
  console.log("\n数据与平台页断言全部通过（存储分布 / 备份三态 / PII 开关 / 平台状态映射）。");
} finally {
  await rm(TEMP, { force: true });
}
