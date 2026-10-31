import { useEffect } from "react";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, TopBar } from "../components/primitives.js";
import { formatRelativeTime } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { HostTasksState } from "./useHostConnection.js";

interface TasksPageProps {
  readonly tasks: HostTasksState;
  /** 能力位 / 运行时「不支持」判断。 */
  readonly supported: boolean;
  readonly onRequest: () => void;
  readonly onBack: () => void;
}

/**
 * 定时任务页（只读）。
 *
 * 一行一个任务：名字 + 启用状态、cron 原样展示、下次执行（未来时间，走相对时间）、
 * 上次执行与上次结果。本批不做启停与编辑，页面底部写明在手机上管理。
 */
export function TasksPage({ tasks, supported, onRequest, onBack }: TasksPageProps) {
  const { t, locale } = useI18n();

  useEffect(() => {
    if (supported) onRequest();
  }, [supported, onRequest]);

  const now = Date.now();

  return (
    <Screen fill topBar={<TopBar title={t("tasks.title")} largeTitle onBack={onBack} />}>
      {!supported || tasks.unsupported ? (
        <div className="muse-stack">
          <CardGroup>
            <ListRow title={t("tasks.unsupported")} divider={false} />
          </CardGroup>
        </div>
      ) : tasks.error !== undefined ? (
        <ErrorStateBox message={tasks.error} onRetry={onRequest} />
      ) : tasks.loading && tasks.items.length === 0 ? (
        <ListSkeleton label={t("tasks.loading")} />
      ) : tasks.items.length === 0 ? (
        <div className="muse-stack">
          <CardGroup>
            <ListRow title={t("tasks.empty")} divider={false} />
          </CardGroup>
          <CardGroup>
            <ListRow title={t("tasks.readOnly")} divider={false} />
          </CardGroup>
        </div>
      ) : (
        <div className="muse-stack">
          {tasks.items.map((task) => (
            <article className="host-memory-item" key={task.id}>
              <div className="host-task-head">
                <span className="host-task-name">{task.name}</span>
                <span className={task.enabled ? "host-task-state is-on" : "host-task-state"}>
                  {task.enabled ? t("tasks.enabled") : t("tasks.disabled")}
                </span>
              </div>
              <code className="host-task-cron">{task.cron}</code>
              <div className="host-task-meta">
                <span className="host-task-meta-label">{t("tasks.nextRun")}</span>
                <span className="host-task-meta-value">
                  {task.nextRunAt === undefined ? t("tasks.never") : formatRelativeTime(task.nextRunAt, now, locale)}
                </span>
              </div>
              <div className="host-task-meta">
                <span className="host-task-meta-label">{t("tasks.lastRun")}</span>
                <span className="host-task-meta-value">
                  {task.lastRunAt === undefined ? t("tasks.never") : formatRelativeTime(task.lastRunAt, now, locale)}
                </span>
              </div>
              {task.lastResult !== undefined && task.lastResult.length > 0 && (
                <div className="host-task-meta">
                  <span className="host-task-meta-label">{t("tasks.lastResult")}</span>
                  <span className="host-task-meta-value">{task.lastResult}</span>
                </div>
              )}
            </article>
          ))}
          <CardGroup>
            <ListRow title={t("tasks.readOnly")} divider={false} />
          </CardGroup>
        </div>
      )}
    </Screen>
  );
}
