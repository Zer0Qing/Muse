import { useCallback, useEffect, useMemo, useState } from "react";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, TopBar } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { formatDuration, formatNumber } from "../i18n/format.js";
import { copyTextToClipboard } from "./clipboard.js";
import type { HostRuntimeState } from "./useHostConnection.js";

type RuntimeTab = "logs" | "diagnostics" | "stats";

const TABS: readonly { readonly id: RuntimeTab; readonly labelKey: string }[] = [
  { id: "logs", labelKey: "runtime.tab.logs" },
  { id: "diagnostics", labelKey: "runtime.tab.diagnostics" },
  { id: "stats", labelKey: "runtime.tab.stats" },
];

interface HostRuntimePageProps {
  readonly title: string;
  readonly runtime: HostRuntimeState;
  readonly supported: boolean;
  readonly onRequestLogs: (limit?: number) => void;
  readonly onRequestDiagnostics: () => void;
  readonly onRequestStats: () => void;
  readonly onBack: () => void;
}

/**
 * 「运行」页：日志尾部 / 诊断快照 / 窗口统计三个 Tab。
 *
 * 三条命令都在 Host 侧定好口径（行数上限、单行截断、脱敏字段、样本充足性），
 * 页面只负责显示，不再自己裁一遍 —— 两处各裁一次迟早会给出两个不同的答案。
 *
 * Tab 首次打开才发对应命令：只想看日志的人不应该顺手触发一次全量诊断采集。
 */
export function HostRuntimePage({
  title,
  runtime,
  supported,
  onRequestLogs,
  onRequestDiagnostics,
  onRequestStats,
  onBack,
}: HostRuntimePageProps) {
  const { t, locale } = useI18n();
  const [tab, setTab] = useState<RuntimeTab>("logs");
  const [copied, setCopied] = useState(false);

  const requestTab = useCallback(
    (target: RuntimeTab, force: boolean) => {
      if (!supported) return;
      if (target === "logs" && (force || runtime.logs === undefined)) onRequestLogs();
      if (target === "diagnostics" && (force || runtime.diagnostics === undefined)) onRequestDiagnostics();
      if (target === "stats" && (force || runtime.stats === undefined)) onRequestStats();
    },
    [supported, runtime.logs, runtime.diagnostics, runtime.stats, onRequestLogs, onRequestDiagnostics, onRequestStats],
  );

  useEffect(() => {
    requestTab(tab, false);
  }, [tab, requestTab]);

  const diagnostics = runtime.diagnostics;
  const prettyDiagnostics = useMemo(() => {
    if (diagnostics === undefined) return undefined;
    try {
      return { text: `${JSON.stringify(JSON.parse(diagnostics), null, 2)}\n`, parsed: true };
    } catch {
      return { text: diagnostics, parsed: false };
    }
  }, [diagnostics]);

  const copyDiagnostics = useCallback(() => {
    const payload = prettyDiagnostics?.text;
    if (payload === undefined) return;
    void copyTextToClipboard(payload).then((ok) => {
      if (!ok) return;
      setCopied(true);
      setTimeout(() => setCopied(false), 2_000);
    });
  }, [prettyDiagnostics]);

  const stats = runtime.stats;
  const loading =
    (tab === "logs" && runtime.logsLoading) || (tab === "diagnostics" && runtime.diagnosticsLoading) || (tab === "stats" && runtime.statsLoading);

  const body = () => {
    if (!supported || runtime.unsupported) {
      return (
        <CardGroup>
          <ListRow title={t("runtime.unsupported")} divider={false} />
        </CardGroup>
      );
    }
    if (runtime.error !== undefined) return <ErrorStateBox message={runtime.error} onRetry={() => requestTab(tab, true)} />;

    const tabPanel = () => {
      if (loading) return <ListSkeleton groups={1} rows={4} label={t("runtime.loading")} />;
      if (tab === "logs") {
        const lines = runtime.logs ?? [];
        if (lines.length === 0) {
          return (
            <CardGroup>
              <ListRow title={t("runtime.empty")} divider={false} />
            </CardGroup>
          );
        }
        return (
          <CardGroup title={t("runtime.newestFirst")}>
            <pre className="host-runtime-logs">
              {[...lines].reverse().map((line, index) => (
                <div key={`${lines.length - index}-${line.slice(0, 24)}`} className="host-runtime-log-line">
                  {line}
                </div>
              ))}
            </pre>
          </CardGroup>
        );
      }
      if (tab === "diagnostics") {
        if (prettyDiagnostics === undefined) {
          return (
            <CardGroup>
              <ListRow title={t("runtime.noDiagnostics")} divider={false} />
            </CardGroup>
          );
        }
        return (
          <CardGroup title={t("runtime.tab.diagnostics")}>
            <ListRow
              title={prettyDiagnostics.parsed ? t("runtime.copyJson") : t("runtime.jsonInvalid")}
              trailing={
                <button type="button" className="host-runtime-action" onClick={copyDiagnostics}>
                  {copied ? t("runtime.copied") : t("runtime.copyJson")}
                </button>
              }
              divider
            />
            <pre className="host-runtime-logs host-runtime-json">{prettyDiagnostics.text}</pre>
          </CardGroup>
        );
      }
      if (stats === undefined) {
        return (
          <CardGroup>
            <ListRow title={t("runtime.empty")} divider={false} />
          </CardGroup>
        );
      }
      return (
        <>
          <CardGroup title={t("runtime.tab.stats")}>
            <ListRow
              title={t("runtime.samples")}
              trailing={<span className="host-setting-value">{formatNumber(stats.webSamples, locale)}</span>}
              divider
            />
            <ListRow
              title={t("runtime.p95")}
              subtitle={stats.webP95Reliable ? undefined : t("runtime.p95FewSamples")}
              trailing={
                <span className="host-setting-value">
                  {stats.webP95Ms === undefined ? "—" : formatDuration(stats.webP95Ms, locale)}
                </span>
              }
              divider={false}
            />
          </CardGroup>
          <CardGroup title={t("runtime.tab.stats")}>
            <ListRow
              title={t("runtime.jank")}
              trailing={
                <span className="host-setting-value">
                  {`${formatNumber(stats.jankCount, locale)} / ${formatNumber(stats.slowCount, locale)} / ${formatNumber(stats.blockCount, locale)}`}
                </span>
              }
              divider
            />
            <ListRow
              title={t("runtime.worst")}
              trailing={<span className="host-setting-value">{formatDuration(stats.worstMs, locale)}</span>}
              divider
            />
            <ListRow
              title={t("runtime.window")}
              trailing={<span className="host-setting-value">{formatDuration(stats.windowSeconds * 1_000, locale)}</span>}
              divider={false}
            />
          </CardGroup>
        </>
      );
    };

    return (
      <>
        <div className="host-runtime-tabs" role="tablist">
          {TABS.map((item) => (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={tab === item.id}
              className={tab === item.id ? "host-runtime-tab is-selected" : "host-runtime-tab"}
              onClick={() => setTab(item.id)}
            >
              {t(item.labelKey)}
            </button>
          ))}
          <button type="button" className="host-runtime-action" onClick={() => requestTab(tab, true)}>
            {t("runtime.refresh")}
          </button>
        </div>
        <div role="tabpanel" className="muse-stack">
          {tabPanel()}
        </div>
      </>
    );
  };

  return (
    <Screen fill topBar={<TopBar title={title} largeTitle onBack={onBack} />}>
      <div className="muse-stack">{body()}</div>
    </Screen>
  );
}
