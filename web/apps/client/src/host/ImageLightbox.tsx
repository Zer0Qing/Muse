import { useEffect, useRef } from "react";
import { CloseIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface ImageLightboxProps {
  readonly src: string;
  readonly onClose: () => void;
}

/** 可聚焦控件的选择器：焦点陷阱只在这些之间循环。 */
const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Tab 循环的下一个下标（纯函数，便于断言）。
 *
 * `active` 为 -1 表示焦点不在对话框里：正向回到第 0 个，反向回到最后一个。
 */
export function trapNextIndex(count: number, active: number, shift: boolean): number {
  if (count <= 0) return -1;
  if (active < 0) return shift ? count - 1 : 0;
  if (shift) return active === 0 ? count - 1 : active - 1;
  return active === count - 1 ? 0 : active + 1;
}

/**
 * 图片全屏查看。
 *
 * 点遮罩关闭、Esc 关闭、「关闭」按钮关闭；打开时把焦点移进对话框、
 * Tab / Shift+Tab 在内部循环（焦点陷阱），关闭后把焦点还给原来的元素。
 */
export function ImageLightbox({ src, onClose }: ImageLightboxProps) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
    closeRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab") return;
      const root = dialogRef.current;
      if (root === null) return;
      const nodes = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
      const active = document.activeElement instanceof HTMLElement ? nodes.indexOf(document.activeElement) : -1;
      const next = trapNextIndex(nodes.length, active, event.shiftKey);
      if (next < 0) return;
      event.preventDefault();
      nodes[next]?.focus();
    };

    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      previous?.focus();
    };
  }, [onClose]);

  return (
    <div
      className="host-lightbox"
      role="dialog"
      aria-modal="true"
      aria-label={t("image.previewTitle")}
      ref={dialogRef}
      onClick={onClose}
    >
      <button
        className="quiet host-lightbox-close"
        type="button"
        aria-label={t("image.previewClose")}
        ref={closeRef}
        onClick={onClose}
      >
        <CloseIcon />
      </button>
      <img className="host-lightbox-image" src={src} alt={t("image.previewTitle")} onClick={(event) => event.stopPropagation()} />
    </div>
  );
}
