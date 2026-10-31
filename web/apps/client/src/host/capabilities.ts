/**
 * Host 的能力位协商。
 *
 * host 侧 `capabilityFlags` 是「这份 Host 实现了什么」的声明，网页据此开关界面，
 * 免得对着一堆永远失败的交互点按：
 *  - `media`：imageUrls / voiceUrl 是可访问地址（`/api/media/<token>`）；
 *  - `tool_call_details`：消息带 `toolCalls[]` 明细；
 *  - `message_status`：消息带 `status` / `errorText`，并且认 `chat.retry`；
 *  - `settings_values`：`settings.snapshot` 带 `values[]`，且 `settings.update` 接受按类型校验的写入；
 *  - `message_write`：认 message.quote / message.favorite / message.delete；
 *  - `message_multi_select`：消息多选 + 批量写（删除/收藏）。
 *
 * 关键区分：**没下发** capabilityFlags（老 Host）与「下发了但没这一位」不是一回事。
 * 前者按老行为继续工作（能渲染的照渲染，取不到的给占位），后者才是明确关闭。
 * 否则一次协议升级就会把老 Host 的图片和状态全抹掉。
 * 例外是消息写操作：老 Host 根本没有这三条命令，所以缺省按「不可写」处理。
 */
export interface HostCapabilities {
  /** 是否收到过 capabilityFlags。false = 老 Host，所有能力按老行为兜底。 */
  readonly known: boolean;
  readonly media: boolean;
  readonly toolCallDetails: boolean;
  readonly messageStatus: boolean;
  /** settings.snapshot 带 `values[]`，且 `settings.update` 接受按类型校验的写入。 */
  readonly settingsValues: boolean;
  /** 引用 / 收藏 / 删除。 */
  readonly messageWrite: boolean;
  /** 多选与批量操作。 */
  readonly multiSelect: boolean;
}

export const LEGACY_CAPABILITIES: HostCapabilities = {
  known: false,
  media: true,
  toolCallDetails: true,
  messageStatus: true,
  // 老 Host 根本没有 values[]，也不认数字/枚举写入：子页走「暂不支持在网页端调整」。
  settingsValues: false,
  // 同理：没有 message.delete / message.favorite，老 Host 只留复制。
  messageWrite: false,
  multiSelect: false,
};

export function readCapabilities(flags: readonly string[] | undefined): HostCapabilities {
  if (flags === undefined) return LEGACY_CAPABILITIES;
  const set = new Set(flags);
  return {
    known: true,
    media: set.has("media"),
    toolCallDetails: set.has("tool_call_details"),
    messageStatus: set.has("message_status"),
    settingsValues: set.has("settings_values"),
    messageWrite: set.has("message_write"),
    multiSelect: set.has("message_multi_select"),
  };
}
