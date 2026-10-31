/**
 * Muse 图标最小集 —— 内联 SVG，pathData 照搬
 * `app/src/main/java/io/zer0/muse/ui/common/icons/MuseIcons.kt`（同一份 24×24 视口、
 * 圆头圆角描边）。线宽与尺寸来自 tokens.json：`.muse-icon` 用
 * `--muse-icons-stroke-width`（1.7）与 `--muse-icons-icon`（24），需要别的档位就传 `size`。
 *
 * 这些图标与 `icons.tsx`（Host 手机壳自带的一套）并存：那边是既有实现的观感，
 * 改它会让现有界面回归，因此新基元统一走这一份。
 */
import type { SVGProps } from "react";
import "./primitives.css";

type MuseIconProps = {
  /** 覆盖默认视觉尺寸（默认取 --muse-icons-icon 令牌）。 */
  readonly size?: number;
  readonly className?: string;
};

function iconProps(size: number | undefined, className: string | undefined): SVGProps<SVGSVGElement> {
  const props: SVGProps<SVGSVGElement> = {
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    className: className ?? "muse-icon",
    "aria-hidden": true,
    focusable: false,
  };
  if (size !== undefined) {
    props.width = size;
    props.height = size;
  }
  return props;
}

/** 返回：arrow-left（两条描边，横线与折角）。 */
export function IconBack({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M19 12H5" />
      <path d="M11 5l-7 7 7 7" />
    </svg>
  );
}

/** 更多：more-horizontal（三点）。 */
export function IconMore({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M6 12h.01M12 12h.01M18 12h.01" />
    </svg>
  );
}

/** 发送：send（实心纸飞机，MuseIcons.send 就是 fill 而非描边）。 */
export function IconSend({ size, className }: MuseIconProps) {
  const props = iconProps(size, className);
  return (
    <svg {...props} fill="currentColor" stroke="none">
      <path d="M20.2 3.8L3.9 10.9a.6.6 0 0 0 .07 1.13l5.9 1.9 1.9 5.9a.6.6 0 0 0 1.13.07L20.2 3.8z" />
    </svg>
  );
}

/** 附件：paperclip。 */
export function IconAttach({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M16.6 9.4l-6.2 6.2a2.1 2.1 0 0 0 3 3l6.2-6.2a4.1 4.1 0 0 0-5.8-5.8L6.6 13.6a6.1 6.1 0 0 0 8.6 8.6l5.6-5.5" />
    </svg>
  );
}

/** 麦克风：microphone（胶囊 + 支架弧 + 立柱）。 */
export function IconMic({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M12 3.5h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z" />
      <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0" />
      <path d="M12 18v2.5" />
    </svg>
  );
}

/** 关闭：x。 */
export function IconClose({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

/** 错误提示：alert-circle（圆 + 竖线 + 点）。 */
export function IconAlertCircle({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0" />
      <path d="M12 7.5V13" />
      <path d="M12 16.5h.01" />
    </svg>
  );
}

/** 右箭头：chevron-right。 */
export function IconChevronRight({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M9.5 6l6 6-6 6" />
    </svg>
  );
}
