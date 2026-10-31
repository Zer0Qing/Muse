import type { MessageKey } from "../i18n/messages.js";

/**
 * 消息操作菜单与批量操作的纯逻辑。
 *
 * 菜单项只由三件事决定：角色、能力位、当前收藏状态。抽成纯函数是为了能脱离 DOM
 * 断言「缺 message_write 只剩复制」「tool / system 只给复制」这类规则；
 * 菜单项只给**词条 key**，文案由组件自己按当前语言翻译（本模块不碰语言）。
 */
export type MessageActionId = "copy" | "quote" | "favorite" | "unfavorite" | "delete" | "select";

export interface MessageActionItem {
  readonly id: MessageActionId;
  readonly labelKey: MessageKey;
  /** 删除是破坏性操作，样式上单独标色。 */
  readonly danger?: boolean;
}

export interface MessageActionInput {
  readonly role: "system" | "user" | "assistant" | "tool";
  readonly hasContent: boolean;
  readonly favorited: boolean;
  readonly capabilities: { readonly messageWrite: boolean; readonly multiSelect: boolean };
}

/**
 * 菜单项（顺序即渲染顺序）：
 *  - 复制：有正文就给；
 *  - 引用 / 收藏（或取消收藏）/ 删除：需要 message_write；
 *  - 多选：需要 message_multi_select；
 *  - role=tool / system：只给复制——这两类不是「对话消息」，删了或引用都没有意义。
 */
export function actionsForMessage(input: MessageActionInput): readonly MessageActionItem[] {
  const items: MessageActionItem[] = [];
  if (input.hasContent) items.push({ id: "copy", labelKey: "common.copy" });
  if (input.role === "tool" || input.role === "system") return items;
  if (input.capabilities.messageWrite) {
    items.push({ id: "quote", labelKey: "common.quote" });
    items.push(input.favorited ? { id: "unfavorite", labelKey: "common.unfavorite" } : { id: "favorite", labelKey: "common.favorite" });
  }
  if (input.capabilities.multiSelect) items.push({ id: "select", labelKey: "common.select" });
  if (input.capabilities.messageWrite) items.push({ id: "delete", labelKey: "common.delete", danger: true });
  return items;
}

/** 被引用内容的摘要：压平换行、截断到一行，输入区上方那条不能撑高。 */
export function quoteExcerpt(text: string, max = 80): string {
  const flat = text.replace(/\s+/g, " ").trim();
  if (flat.length <= max) return flat;
  return `${flat.slice(0, max)}…`;
}

/** 多选复制：只拼有正文的消息，空行分隔。 */
export function selectionCopyText(messages: readonly { readonly content: string }[]): string {
  return messages
    .map((message) => message.content.trim())
    .filter((content) => content.length > 0)
    .join("\n\n");
}

/** 删除确认用哪条词条：单条 / 批量。文案由调用方用 t() 取。 */
export function deleteConfirmKey(count: number): MessageKey {
  return count <= 1 ? "message.deleteConfirmOne" : "message.deleteConfirmMany";
}
