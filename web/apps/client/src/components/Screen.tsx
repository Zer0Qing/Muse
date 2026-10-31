import type { ReactNode } from "react";
import "./primitives.css";

export interface ScreenProps {
  readonly children?: ReactNode;
  /** 顶栏区域（通常传 <TopBar />）。渲染时会补状态栏安全区。 */
  readonly topBar?: ReactNode;
  /** 底部固定区（输入岛 / 主操作）。渲染时会补底部安全区。 */
  readonly footer?: ReactNode;
  /** 内容区是否套屏幕边距（MusePaddings.screen）。默认 true。 */
  readonly padded?: boolean;
  /** 在固定定位的手机壳（flex 列）里撑满可用高度，默认 false。 */
  readonly fill?: boolean;
  readonly className?: string;
}

/**
 * 页面骨架：顶栏 + 滚动内容区 + 底部区，外加安全区内边距。
 *
 * 对应 Compose 的 MusePageScaffold（contentWindowInsets = 0，状态栏交给 topBar、
 * 底部交给 footer），区别只是 Web 用 CSS 安全区变量 env(safe-area-inset-*)。
 */
export function Screen({ children, topBar, footer, padded = true, fill = false, className }: ScreenProps) {
  const classes = ["muse-screen"];
  if (fill) classes.push("is-fill");
  if (className !== undefined) classes.push(className);
  return (
    <div className={classes.join(" ")}>
      {topBar !== undefined && <div className="muse-screen-topbar">{topBar}</div>}
      <div className={padded ? "muse-screen-body is-padded" : "muse-screen-body"}>{children}</div>
      {footer !== undefined && <div className="muse-screen-footer">{footer}</div>}
    </div>
  );
}
