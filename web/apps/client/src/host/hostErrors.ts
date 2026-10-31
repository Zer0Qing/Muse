import type { MessageKey } from "../i18n/messages.js";
import type { Translate } from "../i18n/translate.js";

/**
 * Host 错误码 → 词条。
 *
 * `session.*` 这类写命令最常见的失败是焦点被别的设备抢走（Host 回 `focus_not_held`）：
 * 直接把这串英文丢给用户，他只会以为功能坏了。认识的码换成动作指引，
 * 不认识的保持原样——Host 给的原因通常已经可读，不要吞掉。
 */
export const HOST_ERROR_KEYS: Readonly<Record<string, MessageKey>> = {
  focus_not_held: "error.focusNotHeld",
  session_not_found: "error.sessionNotFound",
  message_not_found: "error.messageNotFound",
  message_not_in_current_session: "error.messageNotInSession",
  assistant_not_found: "error.assistantNotFound",
};

export function describeHostError(code: string | undefined, fallback: string, t: Translate): string {
  if (code === undefined) return fallback;
  const key = code.trim();
  if (key.length === 0) return fallback;
  const messageKey = HOST_ERROR_KEYS[key];
  return messageKey === undefined ? key : t(messageKey);
}
