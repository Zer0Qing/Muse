import { CapsuleButton } from "./CapsuleButton.js";
import { IconAlertCircle } from "./muse-icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import "./primitives.css";

export interface ErrorStateBoxProps {
  readonly message: string;
  /** 传了才渲染「重试」。 */
  readonly onRetry?: () => void;
  /** 传了才渲染「关闭」。 */
  readonly onDismiss?: () => void;
}

/**
 * 错误状态块（可重试）。
 *
 * 对应 Compose 的 MuseErrorStateBox：圆形 error 色底（8%）+ error @60% 图标、
 * 居中 titleMedium 文案、下方一排胶囊按钮。尺寸走 iconEmpty(64) + screen*2 的外圈。
 */
export function ErrorStateBox({ message, onRetry, onDismiss }: ErrorStateBoxProps) {
  const { t } = useI18n();
  const hasActions = onRetry !== undefined || onDismiss !== undefined;
  return (
    <div className="muse-error-box" role="alert">
      <span className="muse-error-box-icon">
        <IconAlertCircle className="muse-icon" />
      </span>
      <p className="muse-error-box-message">{message}</p>
      {hasActions && (
        <div className="muse-error-box-actions">
          {onRetry !== undefined && (
            <CapsuleButton variant="secondary" fillWidth={false} onClick={onRetry}>
              {t("common.retry")}
            </CapsuleButton>
          )}
          {onDismiss !== undefined && (
            <CapsuleButton variant="secondary" fillWidth={false} onClick={onDismiss}>
              {t("common.close")}
            </CapsuleButton>
          )}
        </div>
      )}
    </div>
  );
}
