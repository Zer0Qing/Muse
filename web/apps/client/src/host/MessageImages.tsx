import { useState } from "react";
import { useI18n } from "../i18n/I18nProvider.js";
import type { MessageImage } from "./messageMedia.js";

interface MessageImagesProps {
  /** 已归一过的图片地址（见 messageMedia.ts）。 */
  readonly images: readonly MessageImage[];
  /** 点击某张图打开全屏查看。 */
  readonly onOpen?: ((src: string) => void) | undefined;
}

/**
 * 气泡里的图片。
 *
 * - 单图限高（`42vh`，和折叠块 body 的上限一致），多图走两列格子；
 * - 点开是全屏查看；
 * - 加载失败或地址不可取时给占位块，绝不让浏览器画一个破图图标。
 */
export function MessageImages({ images, onOpen }: MessageImagesProps) {
  const { t } = useI18n();
  if (images.length === 0) return null;
  const grid = images.length > 1;
  return (
    <div className={grid ? "host-bubble-images is-grid" : "host-bubble-images"}>
      {images.map((image, index) =>
        image.kind === "unavailable" ? (
          <span className="host-bubble-image-missing" key={`missing-${index}`} role="status">
            {image.reason === "local-file" ? t("image.missingLocal") : t("image.unavailable")}
          </span>
        ) : (
          <ImageTile key={`${image.src}-${index}`} src={image.src} onOpen={onOpen} />
        ),
      )}
    </div>
  );
}

function ImageTile({ src, onOpen }: { readonly src: string; readonly onOpen?: ((src: string) => void) | undefined }) {
  const { t } = useI18n();
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span className="host-bubble-image-missing" role="status">
        {t("image.loadFailed")}
      </span>
    );
  }
  return (
    <button className="quiet host-bubble-image" type="button" aria-label={t("image.view")} onClick={() => onOpen?.(src)}>
      <img src={src} alt={t("image.alt")} loading="lazy" decoding="async" onError={() => setFailed(true)} />
    </button>
  );
}
