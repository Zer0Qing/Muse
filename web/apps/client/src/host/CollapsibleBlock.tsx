import type { ReactNode } from "react";

/**
 * Host 聊天页的折叠块：工具调用与思考过程共用一套外观（默认折叠，标题行 = 图标 + 标题 + 状态）。
 *
 * 用原生 `<details>`：折叠状态由浏览器维护，重连推快照时不会因为 React 重建而丢失展开内容。
 */
export interface CollapsibleBlockProps {
  readonly icon: ReactNode;
  readonly label: string;
  /** 标题行右侧的状态文案；没有就不渲染。 */
  readonly status?: string | undefined;
  /** running / awaiting 用主色点出「还在动」，其余走中性灰。 */
  readonly tone?: "neutral" | "accent" | "warn";
  readonly children: ReactNode;
}

export function CollapsibleBlock({ icon, label, status, tone = "neutral", children }: CollapsibleBlockProps) {
  return (
    <details className="host-details">
      <summary>
        <span className="host-details-icon">{icon}</span>
        <span className="host-details-label">{label}</span>
        {status !== undefined && status.length > 0 && (
          <span className="host-details-status" data-tone={tone}>
            {tone === "accent" && <span className="host-details-status-dot" aria-hidden="true" />}
            {status}
          </span>
        )}
        <span className="host-details-chevron" aria-hidden="true" />
      </summary>
      <div className="host-details-body">{children}</div>
    </details>
  );
}
