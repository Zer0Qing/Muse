/** Inline stroke icons used by the Host mobile chrome (no icon font / runtime dependency). */
type IconProps = { readonly size?: number };

function base(size: number) {
  return {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.9,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
    focusable: false,
  };
}

export function MenuIcon({ size = 22 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M4 7h16M4 12h16M4 17h11" />
    </svg>
  );
}

export function PlusIcon({ size = 18 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

export function SendIcon({ size = 20 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 19V5M6 11l6-6 6 6" />
    </svg>
  );
}

export function StopIcon({ size = 18 }: IconProps) {
  return (
    <svg {...base(size)}>
      <rect x="7" y="7" width="10" height="10" rx="2.4" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function ArrowDownIcon({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 5v14M6 13l6 6 6-6" />
    </svg>
  );
}

export function CloseIcon({ size = 22 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

export function CheckIcon({ size = 18 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M5 13l4.5 4.5L19 7" />
    </svg>
  );
}

export function ShieldIcon({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 3.5l7 2.7v5.4c0 4.2-2.9 7.6-7 8.9-4.1-1.3-7-4.7-7-8.9V6.2z" />
      <path d="M12 9v3.6M12 15.6h.01" />
    </svg>
  );
}

export function SparkIcon({ size = 14 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 4v4M12 16v4M4 12h4M16 12h4M6.7 6.7l2.6 2.6M14.7 14.7l2.6 2.6M17.3 6.7l-2.6 2.6M9.3 14.7l-2.6 2.6" />
    </svg>
  );
}

export function ToolIcon({ size = 14 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M14.5 6.5a3.5 3.5 0 104.4 4.4L21 14l-7 7-3.1-3.1a3.5 3.5 0 01-4.4-4.4z" />
    </svg>
  );
}

/** 复制：两层错位的圆角方框（App 长按菜单里「复制」的同款直觉）。 */
export function CopyIcon({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)}>
      <rect x="9" y="9" width="11" height="11" rx="2.6" />
      <path d="M15.5 6.2A2.6 2.6 0 0013 4.5H6.5A2.5 2.5 0 004 7v6.4c0 1.2.8 2.2 1.9 2.4" />
    </svg>
  );
}

/** 失败兜底：一条感叹号 + 一圈，用于单条消息渲染异常时的提示。 */
export function AlertIcon({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8v4.5M12 16h.01" />
    </svg>
  );
}

export function PlayIcon({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M8 5.5l10 6.5-10 6.5z" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function PauseIcon({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)}>
      <rect x="7" y="5.5" width="3.4" height="13" rx="1.2" fill="currentColor" stroke="none" />
      <rect x="13.6" y="5.5" width="3.4" height="13" rx="1.2" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** 语音条左侧的声波：三根不同高度的竖线。 */
export function VoiceIcon({ size = 18 }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M6 10v4M12 7v10M18 10v4" />
    </svg>
  );
}
