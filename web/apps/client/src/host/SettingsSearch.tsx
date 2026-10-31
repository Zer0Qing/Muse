import { useEffect, useMemo, useRef, useState } from "react";
import type { HostSettingsSnapshot } from "../api.js";
import { CloseIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { breadcrumbText, searchSettings, type SettingsSearchHit } from "./settingsQuery.js";

interface SettingsSearchProps {
  readonly snapshot: HostSettingsSnapshot;
  readonly query: string;
  readonly onQueryChange: (next: string) => void;
  readonly onPick: (hit: SettingsSearchHit) => void;
  readonly onClose: () => void;
}

/**
 * 全屏设置搜索。
 *
 * 打开时输入框自动获得焦点，Esc 关闭（焦点不在输入框里也能关）。
 * ↑/↓ 移动选中、Enter 进入；鼠标悬停同步选中，点和键盘走同一条 pick 路径。
 * 结果只在本地从 `settings.snapshot` 算，不触发任何协议请求。
 */
export function SettingsSearch({ snapshot, query, onQueryChange, onPick, onClose }: SettingsSearchProps) {
  const { t } = useI18n();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [active, setActive] = useState(0);
  const hits = useMemo(() => searchSettings(snapshot, query), [snapshot, query]);
  const trimmed = query.trim();

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // 关键词一变就把选中拉回第一条，免得停在已经被过滤掉的项上。
  useEffect(() => {
    setActive(0);
  }, [query]);

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
    <div className="host-search" role="dialog" aria-modal="true" aria-label={t("settings.searchDialog")}>
      <div className="host-search-bar">
        <button className="quiet host-icon-button" type="button" aria-label={t("settings.searchExit")} onClick={onClose}>
          <CloseIcon />
        </button>
        <input
          ref={inputRef}
          className="host-search-input"
          type="search"
          aria-label={t("settings.search")}
          placeholder={t("settings.searchPlaceholder")}
          value={query}
          autoComplete="off"
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setActive((current) => Math.min(hits.length - 1, current + 1));
              return;
            }
            if (event.key === "ArrowUp") {
              event.preventDefault();
              setActive((current) => Math.max(0, current - 1));
              return;
            }
            if (event.key !== "Enter") return;
            const hit = hits[active];
            if (hit === undefined) return;
            event.preventDefault();
            onPick(hit);
          }}
        />
      </div>

      {hits.length > 0 && (
        <div className="host-search-results" role="listbox" aria-label={t("settings.searchResults")}>
          {hits.map((hit, index) => (
            <button
              key={hit.id}
              role="option"
              aria-selected={index === active}
              className={index === active ? "quiet host-search-result is-active" : "quiet host-search-result"}
              onMouseEnter={() => setActive(index)}
              onClick={() => onPick(hit)}
            >
              <span className="host-search-title">{hit.title}</span>
              <span className="host-search-breadcrumb">{breadcrumbText(hit)}</span>
            </button>
          ))}
        </div>
      )}

      {trimmed.length > 0 && hits.length === 0 && (
        <div className="host-search-empty" role="status">
          <p>{t("settings.searchEmpty", { query: trimmed })}</p>
          <button className="quiet host-search-empty-action" type="button" onClick={() => onQueryChange("")}>
            {t("settings.searchClear")}
          </button>
        </div>
      )}

      {trimmed.length === 0 && <p className="host-search-hint">{t("settings.searchHint")}</p>}
    </div>
  );
}
