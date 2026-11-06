/**
 * S6「资源 ↔ App 版本兼容提示」的断言。
 *
 * 这个功能的风险不在「提示没出现」，而在两件相反的事：
 *  1. 没匹配上却不提示（白屏仍然无人知晓）；
 *  2. 匹配上了也提示（用户每天被一条无关的黄条教育，最后连真故障也不看）。
 * 所以 matched / unknown 必须**一个节点都不生成**，与 mismatch 必须生成同等重要。
 *
 * 另外把跨语言的三处对齐钉住：HTML 模板里的占位符、Kotlin 里的 TOKEN 常量、
 * 注入脚本的字段名与客户端读取的字段名 —— 它们分处两种语言，任何一侧改名都不会让
 * 另一侧编译失败，只会让提示静默失效。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".version-guard-check.mjs");
const KOTLIN_TAG = join(ROOT, "..", "..", "..", "app", "src", "main", "java", "io", "zer0", "muse", "web", "HostVersionTag.kt");
const KOTLIN_SERVER = join(ROOT, "..", "..", "..", "app", "src", "main", "java", "io", "zer0", "muse", "web", "WebServer.kt");

const ENTRY = `
export { HostVersionBanner } from "./src/host/HostVersionBanner.tsx";
export { CLIENT_PROTOCOL_VERSION, hostBuildDetails, hostBuildStatus, readHostBuild } from "./src/host/hostBuildGuard.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "version-guard-entry.tsx", loader: "tsx" },
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
const banner = (source) => render(mod.createElement(mod.HostVersionBanner, { source }));

const INDEX_HTML = readFileSync(join(ROOT, "index.html"), "utf8");
const API = readFileSync(join(ROOT, "src", "api.ts"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const APP = readFileSync(join(ROOT, "src", "host", "HostApp.tsx"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
const kotlinTag = readFileSync(KOTLIN_TAG, "utf8");
const kotlinServer = readFileSync(KOTLIN_SERVER, "utf8");

try {
  // ── 1. 只有真不匹配才渲染 ──────────────────────────────────
  const mismatch = banner({ __MUSE_HOST_BUILD__: { appVersion: "2.8.0", protocolVersion: 2 } });
  assert(mismatch.includes("host-version-banner"), "协议不匹配时出现提示条");
  assert(mismatch.includes("2.8.0"), "提示里带 App 版本（用户报障时第一句就要说清）");
  assert(mismatch.includes(">2<") || mismatch.includes("2") , "提示里带协议数字");
  assert(mismatch.includes(String(mod.CLIENT_PROTOCOL_VERSION)), "提示里也说明本页需要哪个协议");
  assert(/role="status"/.test(mismatch), "提示对读屏可达");

  assert(banner({ __MUSE_HOST_BUILD__: { appVersion: "2.8.0", protocolVersion: mod.CLIENT_PROTOCOL_VERSION } }) === "", "协议一致时必须什么都不渲染");
  assert(banner(undefined) === "", "读不到注入（更旧的 App）时不报错也不提示");
  assert(banner({}) === "", "window 上挂了个空对象时不许崩");

  // ── 2. 读取口径从严：坏形状一律当作「没读到」 ───────────────
  assert(mod.readHostBuild({ __MUSE_HOST_BUILD__: { appVersion: "2.8.0", protocolVersion: "2" } }) === undefined, "协议写成字符串必须被拒绝——否则比较恒不相等，每次都弹提示");
  assert(mod.readHostBuild({ __MUSE_HOST_BUILD__: { appVersion: "", protocolVersion: 2 } }) === undefined, "空版本号算没读到");
  assert(mod.readHostBuild({ __MUSE_HOST_BUILD__: { protocolVersion: 2 } }) === undefined, "缺 appVersion 算没读到");
  assert(mod.readHostBuild({ __MUSE_HOST_BUILD__: { appVersion: "2.8.0", protocolVersion: Number.NaN } }) === undefined, "NaN 协议算没读到");
  assert(mod.readHostBuild(null) === undefined, "null 来源不抛异常");
  assert(
    JSON.stringify(mod.readHostBuild({ __MUSE_HOST_BUILD__: { appVersion: "2.8.0", protocolVersion: 2 } })) === JSON.stringify({ appVersion: "2.8.0", protocolVersion: 2 }),
    "正常形状原样读回",
  );

  // ── 3. 状态判定 ────────────────────────────────────────────
  assert(mod.hostBuildStatus(undefined) === "unknown", "没有注入 → unknown（不是 mismatch）");
  assert(mod.hostBuildStatus({ appVersion: "1", protocolVersion: 1 }) === "matched", "协议相同 → matched");
  assert(mod.hostBuildStatus({ appVersion: "1", protocolVersion: 0 }) === "protocol-mismatch", "协议 0 也不许被当成相同");
  assert(mod.hostBuildStatus({ appVersion: "1", protocolVersion: 1 }, 2) === "protocol-mismatch", "以传入协议为准");
  assert(mod.hostBuildDetails(undefined).appVersion === "—", "读不到时给占位符而不是 undefined");

  // ── 4. 跨语言对齐：占位符 / 字段名 / 协议常量 ───────────────
  assert(INDEX_HTML.includes("<!--MUSE_HOST_BUILD-->"), "index.html 里保留着注入占位符");
  assert(kotlinTag.includes('const val TOKEN = "<!--MUSE_HOST_BUILD-->"'), "Kotlin 侧的 TOKEN 与模板一致");
  for (const key of ["appVersion", "protocolVersion"]) {
    assert(kotlinTag.includes(`\\"${key}\\"`), `注入脚本带 ${key}`);
    assert(mod.readHostBuild({ __MUSE_HOST_BUILD__: { appVersion: "2.8.0", protocolVersion: 3 } })?.[key] !== undefined, `客户端读得到 ${key}`);
  }
  assert(kotlinServer.includes("HostVersionTag.inject") && kotlinServer.includes('assetPath.endsWith(".html"'), "服务端只对 HTML 注入");
  assert(kotlinServer.includes("CURRENT_PROTOCOL_VERSION"), "服务端下发的是协议版本的唯一真源，不是抄一个 1");
  assert(API.includes("protocolVersion: CLIENT_PROTOCOL_VERSION"), "发命令用的协议版本来自同一个常量");
  assert(!API.includes("protocolVersion: 1"), "api.ts 里不再硬编码协议版本");

  // ── 5. 挂载点 ──────────────────────────────────────────────
  assert(CHAT.includes("<HostVersionBanner />") && (CHAT.match(/\{versionBanner\}/g) ?? []).length === 2, "聊天两种视图都在连接提示条旁边挂了版本提示");
  assert(APP.includes("<HostVersionBanner />"), "登录页也提示（不匹配时登录本身可能就没意义）");
  assert(/\.host-version-banner \{/.test(CSS) && !/\.host-version-banner[^}]*#[0-9a-fA-F]{3,8}/.test(CSS), "提示条有样式且颜色全部来自令牌");

  // ── 6. 五语词条与占位符 ────────────────────────────────────
  for (const name of ["ZH", "EN", "JA", "KO", "RU"]) {
    const dict = mod[name];
    assert(typeof dict["host.version.mismatch"] === "string" && dict["host.version.mismatch"].length > 0, `${name} 有不匹配提示文案`);
    assert(typeof dict["host.version.appLabel"] === "string", `${name} 有 App 版本行`);
    for (const token of ["{hostProtocol}", "{clientProtocol}"]) {
      assert(dict["host.version.mismatch"].includes(token), `${name} 的提示带占位符 ${token}`);
    }
    assert(dict["host.version.appLabel"].includes("{version}"), `${name} 的版本行带 {version}`);
  }

  if (failures > 0) throw new Error(`版本兼容提示断言未通过（${failures} 条）`);
  console.log("\n版本兼容提示断言全部通过（只在不匹配时出现 / 读取口径从严 / 跨语言对齐 / 五语词条）。");
} finally {
  await rm(TEMP, { force: true });
}
