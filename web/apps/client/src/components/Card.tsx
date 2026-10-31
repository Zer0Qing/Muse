import type { ReactNode } from "react";
import { SectionLabel } from "./SectionLabel.js";
import "./primitives.css";

export interface CardProps {
  readonly children?: ReactNode;
  /** 内边距：true 时用 cardInner(16/12)，false 交给内部行自己排（列表分组用）。 */
  readonly padded?: boolean;
  readonly className?: string;
}

/**
 * 圆角卡片：largeCard 圆角(24dp) + 卡面底色 + micro 高程。
 *
 * 对应 Compose CardGroup 里的 MuseSurface(shape = MuseShapes.largeCard,
 * color = colorScheme.surface, elevation = MuseElevation.micro)。
 */
export function Card({ children, padded = false, className }: CardProps) {
  const classes = ["muse-card"];
  if (padded) classes.push("is-padded");
  if (className !== undefined) classes.push(className);
  return <div className={classes.join(" ")}>{children}</div>;
}

export interface CardGroupProps {
  /** 分组标题（渲染为 SectionLabel，位于卡片上方）。 */
  readonly title?: ReactNode;
  readonly children?: ReactNode;
  readonly className?: string;
}

/**
 * 分组容器：可选分组标题 + 一张共享圆角的卡片。
 * 卡片内的行用 ListRow 自己画分隔线（最后一行传 divider={false}）。
 */
export function CardGroup({ title, children, className }: CardGroupProps) {
  const classes = ["muse-card-group"];
  if (className !== undefined) classes.push(className);
  return (
    <section className={classes.join(" ")}>
      {title !== undefined && <SectionLabel>{title}</SectionLabel>}
      <Card>{children}</Card>
    </section>
  );
}
