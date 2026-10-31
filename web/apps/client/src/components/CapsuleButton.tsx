import type { ReactNode } from "react";
import "./primitives.css";

export type CapsuleVariant = "primary" | "secondary";

export interface CapsuleButtonProps {
  readonly children: ReactNode;
  readonly onClick?: () => void;
  /** primary = 品牌主色实心；secondary = 低饱和主色容器。 */
  readonly variant?: CapsuleVariant;
  readonly disabled?: boolean;
  /** 是否撑满可用宽度（MuseCapsuleButton 默认 fillWidth = true）。 */
  readonly fillWidth?: boolean;
  readonly leadingIcon?: ReactNode;
  readonly type?: "button" | "submit";
}

/**
 * 胶囊按钮（主要 / 次要）。
 *
 * 规格取自 MuseCapsuleButton：最小高 48dp、24dp 圆角(MuseShapes.huge)、
 * 水平 20dp / 垂直 16dp 内边距、labelLarge 半粗单行、按压 0.97x(120ms)；
 * 禁用态容器 0.38、文字 0.75（浅色主题下不会变成黑块）。
 */
export function CapsuleButton({
  children,
  onClick,
  variant = "primary",
  disabled = false,
  fillWidth = true,
  leadingIcon,
  type = "button",
}: CapsuleButtonProps) {
  const classes = ["muse-capsule", variant === "primary" ? "is-primary" : "is-secondary"];
  if (fillWidth) classes.push("is-fill");
  return (
    <button className={classes.join(" ")} disabled={disabled} type={type} onClick={onClick}>
      {leadingIcon !== undefined && <span className="muse-capsule-icon">{leadingIcon}</span>}
      <span className="muse-capsule-label">{children}</span>
    </button>
  );
}
