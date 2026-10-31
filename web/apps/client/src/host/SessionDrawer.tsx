import { useEffect, useState } from "react";
import { CloseIcon, PlusIcon } from "../components/icons.js";
import { Switch } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { formatSessionTime } from "./format.js";
import { isRenameValid, normalizeSessionTitle, visibleSessions } from "./sessionActions.js";
import { useDelayedFlag } from "./useDelayedFlag.js";
import { useMediaQuery } from "./useMediaQuery.js";
import type { HostSessionSummary } from "./useHostConnection.js";

/** 与 styles.css 的桌面档断点保持一致（只用于决定左栏要不要预取会话预览）。 */
const DESKTOP_QUERY = "(min-width: 1024px)";

/** 会话面板的数据与回调：抽屉与桌面左栏共用同一份。 */
interface SessionPanelData {
  readonly sessions: readonly HostSessionSummary[];
  /** 首屏快照还没到：列表先上骨架（超过 300ms 才真显），不显示「还没有会话」。 */
  readonly ready: boolean;
  /** 归档会话默认隐藏，打开这个开关才出现。 */
  readonly showArchived: boolean;
  readonly onToggleArchived: () => void;
  readonly currentId: string | undefined;
  readonly previews: Readonly<Record<string, string>>;
  readonly buildId: string;
  readonly onSelect: (sessionId: string) => void;
  readonly onNew: () => void;
  readonly onRequestPreviews: (sessionIds: readonly string[]) => void;
  readonly onRename: (sessionId: string, title: string) => void;
  readonly onArchive: (sessionId: string) => void;
  readonly onDelete: (sessionId: string) => void;
  readonly onOpenSettings: () => void;
  readonly onLogout: () => void;
  /** 仅左栏：收起左栏。抽屉里不传。 */
  readonly onCollapse?: (() => void) | undefined;
}

export interface SessionDrawerProps extends SessionPanelData {
  readonly open: boolean;
  readonly onClose: () => void;
}

export type SessionSidebarProps = SessionPanelData & {
  /** 桌面档左栏是否收起（收起时顶栖会多出一个展开按钮）。 */
  readonly collapsed: boolean;
  readonly onToggleSidebar: () => void;
};

const PREVIEW_BATCH = 40;

/**
 * 会话面板内容。
 *
 * 手机上它是滑出的抽屉（头部带关闭键、底部带设置入口）；桌面档左栏常驻，
 * 头部直接放设置入口、不需要关闭键。列表行两边完全一致——同一套
 * `host-session` 几何（ListRow：高 60、内边距 16/12）。
 *
 * 重命名是行内输入（不走 window.prompt）：空名禁用「保存」，Enter 提交、Esc 取消。
 * 归档会话默认不列，头部有「显示归档」开关。
 */
function SessionPanel({
  variant,
  open,
  onClose,
  ...props
}: SessionPanelData & { readonly variant: "drawer" | "sidebar"; readonly open: boolean; readonly onClose?: (() => void) | undefined }) {
  const {
    sessions,
    ready,
    showArchived,
    onToggleArchived,
    currentId,
    previews,
    buildId,
    onSelect,
    onNew,
    onRequestPreviews,
    onRename,
    onArchive,
    onDelete,
    onOpenSettings,
    onLogout,
    onCollapse,
  } = props;
  const { t, locale } = useI18n();
  const [expandedId, setExpandedId] = useState<string | undefined>(undefined);
  const [renamingId, setRenamingId] = useState<string | undefined>(undefined);
  const [renameDraft, setRenameDraft] = useState("");
  const active = open;
  const skeletonDue = useDelayedFlag(!ready);
  const list = visibleSessions(sessions, showArchived);

  // The snapshot only carries title/time; ask the Host for the latest message of
  // each session once the panel is visible (bounded batch, best effort).
  useEffect(() => {
    if (!active) return;
    const missing = sessions
      .filter((session) => previews[session.id] === undefined)
      .slice(0, PREVIEW_BATCH)
      .map((session) => session.id);
    if (missing.length > 0) onRequestPreviews(missing);
  }, [active, sessions, previews, onRequestPreviews]);

  const cancelRename = () => {
    setRenamingId(undefined);
    setRenameDraft("");
  };

  const commitRename = () => {
    if (renamingId === undefined || !isRenameValid(renameDraft)) return;
    onRename(renamingId, normalizeSessionTitle(renameDraft));
    cancelRename();
  };

  return (
    <>
      <div className="host-drawer-head">
        <div className="host-drawer-title">
          <span>{t("sessions.title")}</span>
          {variant === "drawer" ? (
            <button className="quiet host-drawer-close" type="button" aria-label={t("sessions.close")} onClick={onClose}>
              <CloseIcon size={20} />
            </button>
          ) : (
            <span className="host-sidebar-head-actions">
              <button className="quiet host-sidebar-settings" type="button" onClick={onOpenSettings}>
                {t("common.settings")}
              </button>
              <button
                className="quiet host-sidebar-collapse"
                type="button"
                aria-label={t("sessions.collapse")}
                onClick={onCollapse}
              >
                «
              </button>
            </span>
          )}
        </div>
        <button className="host-new" type="button" onClick={onNew}>
          <PlusIcon />
          {t("chat.newSession")}
        </button>
        <div className="host-archived-toggle">
          <span className="host-archived-label">{t("sessions.showArchived")}</span>
          <Switch checked={showArchived} label={t("sessions.showArchived")} onChange={() => onToggleArchived()} />
        </div>
      </div>

      <div className="host-session-list">
        {!ready && skeletonDue && (
          <div className="host-session-skeleton" role="status" aria-label={t("sessions.loading")}>
            {[0, 1, 2].map((index) => (
              <div className="host-session-skeleton-row" key={index}>
                <span className="muse-skeleton" />
                <span className="muse-skeleton is-thin" />
              </div>
            ))}
          </div>
        )}
        {ready && list.length === 0 && (
          <p className="host-drawer-empty">
            {sessions.length === 0 ? t("sessions.empty") : t("sessions.emptyActive")}
          </p>
        )}
        {ready &&
          list.map((session) => {
            const isActive = session.id === currentId;
            const expanded = expandedId === session.id;
            if (renamingId === session.id) {
              return (
                <div className="host-session-rename" key={session.id}>
                  <input
                    className="host-session-rename-input"
                    aria-label={t("sessions.renameAria", { title: session.title })}
                    value={renameDraft}
                    autoFocus
                    onChange={(event) => setRenameDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        commitRename();
                        return;
                      }
                      if (event.key === "Escape") {
                        event.preventDefault();
                        cancelRename();
                      }
                    }}
                  />
                  <button className="quiet host-session-rename-save" type="button" disabled={!isRenameValid(renameDraft)} onClick={commitRename}>
                    {t("common.save")}
                  </button>
                  <button className="quiet" type="button" onClick={cancelRename}>
                    {t("common.cancel")}
                  </button>
                  {!isRenameValid(renameDraft) && <span className="host-session-rename-hint">{t("sessions.renameEmpty")}</span>}
                </div>
              );
            }
            return (
              <div className={isActive ? "host-session-wrap is-active" : "host-session-wrap"} key={session.id}>
                <button
                  className={isActive ? "host-session active" : "host-session"}
                  type="button"
                  aria-current={isActive ? "true" : undefined}
                  onClick={() => {
                    setExpandedId(undefined);
                    onSelect(session.id);
                  }}
                >
                  <span className="host-session-title">
                    {session.pinned && <span className="host-pin" aria-label={t("sessions.pinned")}>📌</span>}
                    {session.title || t("sessions.untitled")}
                    {session.archived && <span className="host-session-archived">{t("sessions.archived")}</span>}
                  </span>
                  <span className="host-session-meta">
                    <span className="host-session-preview">{previews[session.id] ?? "　"}</span>
                    <span className="host-session-time">{formatSessionTime(session.updatedAt, locale)}</span>
                  </span>
                </button>
                <button
                  className="quiet host-session-more"
                  type="button"
                  aria-label={t("sessions.moreAria", { title: session.title })}
                  aria-expanded={expanded}
                  onClick={() => setExpandedId(expanded ? undefined : session.id)}
                >
                  ⋯
                </button>
                {expanded && (
                  <div className="host-session-actions">
                    <button
                      className="quiet"
                      type="button"
                      onClick={() => {
                        setExpandedId(undefined);
                        setRenamingId(session.id);
                        setRenameDraft(session.title);
                      }}
                    >
                      {t("sessions.rename")}
                    </button>
                    <button className="quiet" type="button" onClick={() => onArchive(session.id)}>
                      {t("sessions.archive")}
                    </button>
                    <button className="quiet host-danger" type="button" onClick={() => onDelete(session.id)}>
                      {t("common.delete")}
                    </button>
                  </div>
                )}
              </div>
            );
          })}
      </div>

      <div className="host-drawer-foot">
        <span className="host-build">{t("sessions.version", { build: buildId })}</span>
        <div className="host-drawer-foot-actions">
          {variant === "drawer" && (
            <button className="quiet" type="button" onClick={onOpenSettings}>
              {t("common.settings")}
            </button>
          )}
          <button className="quiet" type="button" onClick={onLogout}>
            {t("sessions.logout")}
          </button>
        </div>
      </div>
    </>
  );
}

/** 手机 / 平板档的会话抽屉：遮罩 + 滑出面板。桌面档由 CSS 隐藏。 */
export function SessionDrawer(props: SessionDrawerProps) {
  const { t } = useI18n();
  const { open, onClose, ...data } = props;
  return (
    <>
      <button
        className={open ? "host-scrim open" : "host-scrim"}
        type="button"
        aria-label={t("sessions.close")}
        tabIndex={open ? 0 : -1}
        onClick={onClose}
      />
      <aside className={open ? "host-drawer open" : "host-drawer"} aria-label={t("sessions.listAria")} aria-hidden={!open}>
        <SessionPanel variant="drawer" open={open} onClose={onClose} {...data} />
      </aside>
    </>
  );
}

/** 桌面档常驻左栏：同一个面板，去掉遮罩与关闭键。 */
export function SessionSidebar(props: SessionSidebarProps) {
  // 只在左栏真的显示（桌面档、且未收起）时才预取会话预览，免得手机上抽屉没开也发一批请求。
  const { t } = useI18n();
  const desktop = useMediaQuery(DESKTOP_QUERY);
  const { collapsed, onToggleSidebar, ...data } = props;
  return (
    <aside className={collapsed ? "host-sidebar is-collapsed" : "host-sidebar"} aria-label={t("sessions.listAria")}>
      <SessionPanel variant="sidebar" open={desktop && !collapsed} onCollapse={onToggleSidebar} {...data} />
    </aside>
  );
}
