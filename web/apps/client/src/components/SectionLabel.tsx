import type { ReactNode } from "react";
import "./primitives.css";

export interface SectionLabelProps {
  readonly children: ReactNode;
  readonly className?: string;
}

/**
 * 分组小标题。对应 Compose 的 SectionLabel —— labelLarge 半粗、onSurface @80%，
 * 自带 bottom = tightGap(4dp) 的下间距（上方间距由使用方的分组间距提供）。
 */
export function SectionLabel({ children, className }: SectionLabelProps) {
  const classes = ["muse-section-label"];
  if (className !== undefined) classes.push(className);
  return <p className={classes.join(" ")}>{children}</p>;
}
