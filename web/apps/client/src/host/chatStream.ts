import type { HostMessage } from "../api.js";
import type { MessageKey } from "../i18n/messages.js";
import { DEFAULT_LOCALE, type Locale } from "../i18n/locale.js";
import { dayKey, formatDaySeparator } from "./format.js";

/**
 * 消息流的渲染清单：按天在跨界的消息前面插入一条时间分隔。
 *
 * 规则（和 App 的 DateSeparator 对齐，再加网页端的三条约束）：
 *  - 分隔条按天插：相邻两条消息分属不同日历日才打断；
 *  - 同一分钟内的连续消息绝不重复打断——同分钟必然同一天，按天分桶已经保证了这一点；
 *    文案与上一条分隔相同时也不再重复插，时间戳乱序 / 重复的退化数据不会把列表戳成一片分隔条；
 *  - 时间戳缺失 / 非法的消息不参与分桶，只照常渲染，不产生分隔条；
 *  - 列表最上方补一条：网页端没有 App 的会话头部日期可依赖，打开一个历史会话时
 *    必须第一眼知道这批消息是哪天的。
 *
 * key 的唯一性单独处理：Host 的 id 是 UUID，快照里理论上不重复，但分页合并后可能撞上，
 * 重复时补序号，避免 React 复用错节点。
 */

export type StreamItem =
  | { readonly kind: "divider"; readonly key: string; readonly label: string }
  | { readonly kind: "message"; readonly key: string; readonly message: HostMessage; readonly index: number };

export function buildStreamItems(
  messages: readonly HostMessage[],
  now: number = Date.now(),
  locale: Locale = DEFAULT_LOCALE,
): readonly StreamItem[] {
  const items: StreamItem[] = [];
  const usedKeys = new Set<string>();
  let ordinal = 0;
  let previousDay: string | undefined;
  let lastLabel: string | undefined;

  for (const message of messages) {
    const day = dayKey(message.createdAt);
    if (day !== undefined && previousDay !== undefined && day !== previousDay) {
      const label = formatDaySeparator(message.createdAt, now, locale);
      if (label.length > 0 && label !== lastLabel) {
        lastLabel = label;
        items.push({ kind: "divider", key: `divider-${day}-${ordinal}`, label });
      }
    }
    if (day !== undefined) previousDay = day;

    const base = message.id.length > 0 ? message.id : `message-${ordinal}`;
    let key = base;
    for (let index = 2; usedKeys.has(key); index += 1) key = `${base}#${index}`;
    usedKeys.add(key);
    items.push({ kind: "message", key, message, index: ordinal });
    ordinal += 1;
  }

  const first = items[0];
  if (first !== undefined && first.kind === "message") {
    const topDay = dayKey(first.message.createdAt);
    const topLabel = topDay === undefined ? "" : formatDaySeparator(first.message.createdAt, now, locale);
    if (topLabel.length > 0) items.unshift({ kind: "divider", key: `divider-top-${topDay ?? "top"}`, label: topLabel });
  }
  return items;
}

/**
 * 工具调用块的状态。协议里没有消息级 status 字段，这里只用现有字段推断，不做无根据的猜测：
 * 审批表里有同一个 toolCallId → 待确认；整会话仍在生成且是本会话最后一条 → 执行中；
 * 否则 role=tool 的是已完成，助手侧发起的调用是已发起（结果还没回来，或就折在正文里）。
 */
export type ToolStatus = "awaiting" | "running" | "done" | "requested";

export function toolStatusOf(
  message: HostMessage,
  context: { readonly isStreaming: boolean; readonly isLast: boolean; readonly approvalToolCallIds: ReadonlySet<string> },
): ToolStatus {
  if (message.toolCallId !== undefined && context.approvalToolCallIds.has(message.toolCallId)) return "awaiting";
  if (context.isStreaming && context.isLast) return "running";
  if (message.role === "tool") return "done";
  return "requested";
}

export const TOOL_STATUS_KEY: Readonly<Record<ToolStatus, MessageKey>> = {
  awaiting: "tool.status.awaiting",
  running: "tool.status.running",
  done: "tool.status.done",
  requested: "tool.status.requested",
};
