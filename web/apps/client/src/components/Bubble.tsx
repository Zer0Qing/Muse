import type { ReactNode } from "react";
import "./primitives.css";

export type BubbleRole = "user" | "assistant";

export interface BubbleProps {
  /** user 右侧（品牌色），assistant 左侧（暖灰底）。 */
  readonly role: BubbleRole;
  readonly children: ReactNode;
  /** 时间戳 / 模型名等小字。 */
  readonly meta?: ReactNode;
  /** 底部操作条（重新生成 / 继续）。 */
  readonly actions?: ReactNode;
}

/**
 * 消息气泡：用户右、助手左。
 *
 * 规格取自 MuseBubbleStyles / MuseShapes.userBubble / assistantBubble ——
 * 主体圆角 20dp、方向侧尾巴角 6dp、最大宽度 70% 屏宽、内边距 bubbleInner(10/8)、
 * 正文 bodyLarge（1.6 倍行高）。
 */
export function Bubble({ role, children, meta, actions }: BubbleProps) {
  return (
    <div className={`muse-bubble is-${role}`}>
      {meta !== undefined && <div className="muse-bubble-meta">{meta}</div>}
      <div className="muse-bubble-body">{children}</div>
      {actions !== undefined && <div className="muse-bubble-actions">{actions}</div>}
    </div>
  );
}
