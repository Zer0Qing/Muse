import type { HostConnectionStatus } from "../api.js";
import type { MessageKey } from "../i18n/messages.js";

/**
 * 断线提示的纯逻辑。
 *
 * 三态文案要分得清：正在重连（第 N 次）/ 已断开 / 网络已断开（浏览器自己说离线时
 * 不要再显示「正在重连」，那是误导）。这里只给**词条 key + 参数**，
 * 具体文案由组件按当前语言翻译。
 */
export interface ConnectionBanner {
  readonly visible: boolean;
  /** 文案词条；visible=false 时无意义。 */
  readonly textKey: MessageKey;
  readonly params?: { readonly attempt: number } | undefined;
  /** 是否给「手动重试」入口（连续失败到阈值、或已断开）。 */
  readonly retry: boolean;
  readonly tone: "info" | "warn" | "danger";
}

/** 连续重连这么多次还没成功，就给用户一个手动重试的出口。 */
export const MANUAL_RETRY_AFTER = 3;

const HIDDEN: ConnectionBanner = { visible: false, textKey: "connection.connecting", retry: false, tone: "info" };

export function connectionBanner(input: {
  readonly online: boolean;
  readonly status: HostConnectionStatus;
  readonly attempt: number;
  readonly everConnected: boolean;
}): ConnectionBanner {
  const { online, status, attempt, everConnected } = input;
  // 浏览器自己知道没网：这时候的「正在重连」是假象，直接说原因。
  if (!online) return { visible: true, textKey: "connection.offline", retry: false, tone: "danger" };
  if (status === "connected") return HIDDEN;
  if (status === "closed") {
    return {
      visible: true,
      textKey: everConnected ? "connection.disconnected" : "connection.unreachable",
      retry: true,
      tone: "danger",
    };
  }
  if (attempt > 0) {
    return { visible: true, textKey: "connection.reconnecting", params: { attempt }, retry: attempt >= MANUAL_RETRY_AFTER, tone: "warn" };
  }
  return {
    visible: true,
    textKey: everConnected ? "connection.interrupted" : "connection.connecting",
    retry: false,
    tone: "warn",
  };
}

/**
 * 输入框的禁用说明。undefined 表示可以正常输入。
 * 离线优先：没网时说要恢复网络，而不是让用户等一个本来就连不上的重连。
 */
export function composerDisabledHint(input: {
  readonly online: boolean;
  readonly status: HostConnectionStatus;
}): MessageKey | undefined {
  if (!input.online) return "composer.hintOffline";
  if (input.status === "closed") return "composer.hintClosed";
  if (input.status !== "connected") return "composer.hintReconnecting";
  return undefined;
}
