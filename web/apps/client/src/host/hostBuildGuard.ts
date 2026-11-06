/**
 * S6：网页资源与 App 的兼容判断。
 *
 * 背景：Host 的网页资源是打包进 APK 的一份静态产物。它和 App 不是同一次构建时
 * （旧资源 + 新 App，或 sideload 的新资源 + 旧 App），界面会停在启动引导转圈，
 * 用户什么都不知道。这里把它变成一个说得出来的状态。
 *
 * 判据只有协议主版本，**不是 App 版本号**：
 * 协议相同而版本号不同时，功能本来就能正常工作；把每次小版本升级都变成一条黄色横幅，
 * 是在训练用户忽略真正要紧的警告。版本号只作为「发给开发者时的一句话背景」显示。
 */

/** App 侧注入的构建信息（见 HostVersionTag.kt）。 */
export interface HostBuildInfo {
  readonly appVersion: string;
  readonly protocolVersion: number;
}

/** 这份网页资源说的协议版本；与 api.ts 发出命令时用的是同一个常量。 */
export const CLIENT_PROTOCOL_VERSION = 1;

export type HostBuildStatus = "matched" | "protocol-mismatch" | "unknown";

/** 从任意 window 形状里读注入信息；字段缺失或类型不对都算没读到。 */
export function readHostBuild(source: unknown): HostBuildInfo | undefined {
  const injected = (source as { readonly __MUSE_HOST_BUILD__?: unknown } | undefined)?.__MUSE_HOST_BUILD__;
  if (typeof injected !== "object" || injected === null) return undefined;
  const record = injected as { readonly appVersion?: unknown; readonly protocolVersion?: unknown };
  if (typeof record.appVersion !== "string" || record.appVersion.length === 0) return undefined;
  // 协议必须是数字：写成 "2" 会让比较恒成立，于是每次打开都弹提示 —— 那比不提示更糟。
  if (typeof record.protocolVersion !== "number" || !Number.isFinite(record.protocolVersion)) return undefined;
  return { appVersion: record.appVersion, protocolVersion: record.protocolVersion };
}

/**
 * 兼容状态。
 *
 * `unknown` 表示 App 没有注入（更旧的 App，或资源被 Service Worker 缓存成旧的一份）。
 * 此时**不报错**：老版本组合本来就在正常工作，凭空加一条警告是把不确定当失败。
 */
export function hostBuildStatus(
  info: HostBuildInfo | undefined,
  clientProtocol: number = CLIENT_PROTOCOL_VERSION,
): HostBuildStatus {
  if (info === undefined) return "unknown";
  return info.protocolVersion === clientProtocol ? "matched" : "protocol-mismatch";
}

/** 提示条要显示的三个值；文案由 i18n 负责，这里只保证不出现 undefined。 */
export function hostBuildDetails(info: HostBuildInfo | undefined, clientProtocol: number = CLIENT_PROTOCOL_VERSION) {
  return {
    appVersion: info?.appVersion ?? "—",
    hostProtocol: info === undefined ? "—" : String(info.protocolVersion),
    clientProtocol: String(clientProtocol),
  };
}
