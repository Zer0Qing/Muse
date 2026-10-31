import { SETTINGS_ICON_PATHS } from "./settings-icon-paths.js";
import "./primitives.css";

export interface SettingsIconProps {
  /** 设置条目的 `iconKey`（与 `MuseIcons` 里的 `val` 同名）。 */
  readonly iconKey: string;
}

/** 未知 iconKey 的兜底：一个空心圆，保证行高与左侧栏宽度不变。 */
const FALLBACK_PATHS: readonly string[] = ["M5.5 12a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0"];

/**
 * 设置项左侧图标：圆底药丸 + 线条图标。
 *
 * 图形来自 `settings-icon-paths.ts`（照搬 `MuseIcons` 的 pathData），
 * 尺寸与颜色全部走令牌（`--muse-icons-icon-large` 圆底 / `--muse-icons-icon-small` 图形 /
 * `--ui-surface-variant` 底 / `--ui-on-surface-variant` 线），页面里不写数值。
 */
export function SettingsIcon({ iconKey }: SettingsIconProps) {
  const paths = SETTINGS_ICON_PATHS[iconKey] ?? FALLBACK_PATHS;
  return (
    <span className="muse-settings-icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" className="muse-icon" aria-hidden focusable={false}>
        {paths.map((d) => (
          <path key={d} d={d} />
        ))}
      </svg>
    </span>
  );
}
