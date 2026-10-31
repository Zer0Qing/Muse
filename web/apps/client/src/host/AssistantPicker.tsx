import { useEffect } from "react";
import type { HostAssistantSummary } from "../api.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { HostAvatar } from "./HostAvatar.js";

interface AssistantPickerProps {
  readonly assistants: readonly HostAssistantSummary[];
  readonly onPick: (assistant: HostAssistantSummary) => void;
  readonly onClose: () => void;
}

/**
 * 新建会话前的助手选择。
 *
 * 只在助手多于一个时出现（一个直接建、零个交给 Host 的默认助手），
 * 列表就是 `assistants.list` 原样：头像 + 名字 + 默认标记。Esc 关闭。
 * 助手名是 Host 下发的，原样显示。
 */
export function AssistantPicker({ assistants, onPick, onClose }: AssistantPickerProps) {
  const { t } = useI18n();

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="host-picker" role="dialog" aria-modal="true" aria-label={t("assistants.title")}>
      <div className="host-picker-sheet">
        <div className="host-picker-head">
          <h2>{t("assistants.title")}</h2>
          <button className="quiet host-picker-cancel" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
        </div>
        <div className="host-picker-list" role="listbox" aria-label={t("assistants.listAria")}>
          {assistants.map((assistant) => (
            <button
              key={assistant.id}
              className="quiet host-picker-item"
              type="button"
              role="option"
              aria-selected={assistant.isDefault === true}
              onClick={() => onPick(assistant)}
            >
              <HostAvatar name={assistant.name} uri={assistant.avatarUri} size="large" />
              <span className="host-picker-name">{assistant.name}</span>
              {assistant.isDefault === true && <span className="host-picker-badge">{t("assistants.default")}</span>}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
