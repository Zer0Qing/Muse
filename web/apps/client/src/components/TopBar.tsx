import type { ReactNode } from "react";
import { useI18n } from "../i18n/I18nProvider.js";
import { IconBack } from "./muse-icons.js";
import "./primitives.css";

export interface TopBarProps {
  readonly title: string;
  /** 不传则不渲染返回键（与 MuseTopBar 的 `onBack = null` 同义）。 */
  readonly onBack?: () => void;
  /** Large Title：标题独占一行走 displayLarge（34sp），顶栏行只留返回与操作。 */
  readonly largeTitle?: boolean;
  readonly actions?: ReactNode;
  /** 返回键的无障碍文案；不传按当前语言取词条（common.back）。 */
  readonly backLabel?: string;
}

/**
 * 顶栏：返回 / 标题 / 右侧动作。
 *
 * 规格取自 MuseTopBar —— 行内边距 chipInnerTight(4/1)、返回键 48dp 触摸目标、
 * 标题 titleLarge 单行省略、Large Title 用 displayLarge + messageGap/tightGap 内边距。
 */
export function TopBar({ title, onBack, largeTitle = false, actions, backLabel }: TopBarProps) {
  const { t } = useI18n();
  const label = backLabel ?? t("common.back");
  return (
    <header className="muse-topbar">
      <div className="muse-topbar-row">
        {onBack !== undefined && (
          <button className="muse-icon-button" type="button" aria-label={label} onClick={onBack}>
            <IconBack />
          </button>
        )}
        {largeTitle ? <span className="muse-topbar-spacer" /> : <h1 className="muse-topbar-title">{title}</h1>}
        {actions !== undefined && <div className="muse-topbar-actions">{actions}</div>}
      </div>
      {largeTitle && <h1 className="muse-topbar-large-title">{title}</h1>}
    </header>
  );
}
