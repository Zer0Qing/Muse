import "./primitives.css";

export interface SwitchProps {
  readonly checked: boolean;
  readonly onChange: (next: boolean) => void;
  /** 无障碍名称；不传则要求使用方在外部提供文字标签。 */
  readonly label?: string;
  readonly disabled?: boolean;
}

/**
 * 开关。几何与观感对齐 MuseSwitch：轨道 51×31、拇指 27、内缩 2dp（触摸区 48dp），
 * 开启走 primary/onPrimary，关闭走 lerp(surfaceVariant, onSurface, 12%)，
 * 拇指位移与轨道换色同用 tactile(200ms) easeOutCubic。
 */
export function Switch({ checked, onChange, label, disabled = false }: SwitchProps) {
  const classes = ["muse-switch"];
  if (checked) classes.push("is-on");
  return (
    <button
      aria-checked={checked}
      aria-label={label}
      className={classes.join(" ")}
      disabled={disabled}
      role="switch"
      type="button"
      onClick={() => onChange(!checked)}
    >
      <span className="muse-switch-track">
        <span className="muse-switch-thumb" />
      </span>
    </button>
  );
}
