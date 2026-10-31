import type { ReactNode } from "react";
import { IconChevronRight } from "./muse-icons.js";
import "./primitives.css";

export interface ListRowProps {
  readonly title: ReactNode;
  readonly subtitle?: ReactNode;
  /** 左侧内容（通常是图标）。 */
  readonly leading?: ReactNode;
  /** 右侧内容（Switch / 值文字 / 自定义元素）。 */
  readonly trailing?: ReactNode;
  /** 右侧追加 Muse 右箭头（与 trailing 可叠加）。 */
  readonly chevron?: boolean;
  readonly onClick?: () => void;
  /** 底部分隔线，默认 true；分组最后一行传 false。 */
  readonly divider?: boolean;
}

/**
 * 设置项行：标题 + 副标题 + 右侧箭头/开关 + 分隔线。
 *
 * 规格取自 MuseListItem / MuseDivider —— 最小高 60dp、水平 16dp / 垂直 12dp、
 * 标题 titleMedium、副标题 bodyMedium、分隔线 0.5dp 且左缩进 56dp。
 */
export function ListRow({
  title,
  subtitle,
  leading,
  trailing,
  chevron = false,
  onClick,
  divider = true,
}: ListRowProps) {
  const content = (
    <>
      {leading !== undefined && <span className="muse-listrow-leading">{leading}</span>}
      <span className="muse-listrow-text">
        <span className="muse-listrow-title">{title}</span>
        {subtitle !== undefined && <span className="muse-listrow-subtitle">{subtitle}</span>}
      </span>
      {(trailing !== undefined || chevron) && (
        <span className="muse-listrow-trailing">
          {trailing}
          {chevron && <IconChevronRight className="muse-icon muse-listrow-chevron" size={18} />}
        </span>
      )}
      {divider && <span aria-hidden="true" className="muse-listrow-divider" />}
    </>
  );

  if (onClick === undefined) {
    return <div className="muse-listrow">{content}</div>;
  }
  return (
    <button className="muse-listrow is-interactive" type="button" onClick={onClick}>
      {content}
    </button>
  );
}
