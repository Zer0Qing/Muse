import { useEffect, useRef, useState, type ReactNode } from "react";
import { ErrorStateBox, ListRow, ListSkeleton, Screen, TopBar } from "../components/primitives.js";
import { formatRelativeTime } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { HostMemoryState } from "./useHostConnection.js";

interface MemoryPageProps {
  readonly memory: HostMemoryState;
  /** 能力位 / 运行时的「不支持」判断结果。 */
  readonly supported: boolean;
  readonly onRequest: (options?: {
    readonly query?: string | undefined;
    readonly cursor?: string | undefined;
    readonly reset?: boolean | undefined;
  }) => void;
  readonly onBack: () => void;
  /** 页面顶部额外内容（例如记忆的开关卡片）。 */
  readonly children?: ReactNode;
}

/**
 * 记忆页：搜索 + 分页列表。
 *
 * 搜索回车触发 `memory.search`，清空回到 `memory.list`；分页游标用上一页最后一条 id。
 * 滚到底自动续（有 IntersectionObserver 时），没有就退化成显式「加载更多」按钮。
 */
export function MemoryPage({ memory, supported, onRequest, onBack, children }: MemoryPageProps) {
  const { t, locale } = useI18n();
  const [draft, setDraft] = useState("");
  const sentinel = useRef<HTMLDivElement | null>(null);
  const lastId = memory.items.length > 0 ? memory.items[memory.items.length - 1]?.id : undefined;
  const canLoadMore = memory.hasMore && !memory.loading && memory.query === undefined;

  // 首屏拉第一页
  useEffect(() => {
    if (!supported) return;
    onRequest({ reset: true });
  }, [supported, onRequest]);

  // 滚到底自动续（搜索不分页，只在列表态生效）
  useEffect(() => {
    if (!supported || !canLoadMore || lastId === undefined) return;
    const element = sentinel.current;
    if (element === null || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) onRequest({ cursor: lastId });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [supported, canLoadMore, lastId, onRequest]);

  const submitSearch = () => {
    const query = draft.trim();
    onRequest(query.length > 0 ? { query, reset: true } : { reset: true });
  };

  const clearSearch = () => {
    setDraft("");
    onRequest({ reset: true });
  };

  const showUnsupported = !supported || memory.unsupported;
  const searchable = memory.query !== undefined;

  return (
    <Screen fill topBar={<TopBar title={t("memory.title")} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        {children}
        {!showUnsupported && (
          <div className="host-memory-search">
            <input
              className="host-memory-input"
              type="search"
              aria-label={t("memory.searchPlaceholder")}
              placeholder={t("memory.searchPlaceholder")}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key !== "Enter") return;
                event.preventDefault();
                submitSearch();
              }}
            />
            <button className="quiet host-memory-search-action" type="button" onClick={submitSearch}>
              {t("memory.search")}
            </button>
          </div>
        )}

        {showUnsupported ? (
          <ListRow title={t("memory.unsupported")} divider={false} />
        ) : memory.error !== undefined ? (
          <ErrorStateBox message={memory.error} onRetry={() => onRequest({ reset: true })} />
        ) : memory.loading && memory.items.length === 0 ? (
          <ListSkeleton groups={1} rows={4} label={t("memory.loading")} />
        ) : memory.items.length === 0 ? (
          <div className="host-memory-empty">
            <p>{searchable ? t("memory.searchEmpty", { query: memory.query ?? "" }) : t("memory.empty")}</p>
            {searchable && (
              <button className="quiet host-memory-clear" type="button" onClick={clearSearch}>
                {t("memory.clear")}
              </button>
            )}
          </div>
        ) : (
          <div className="host-memory-list">
            {memory.items.map((item) => (
              <article className="host-memory-item" key={item.id}>
                <p className="host-memory-content">{item.content}</p>
                <div className="host-memory-meta">
                  {item.pinned === true && <span className="host-memory-pin">{t("memory.pinned")}</span>}
                  {item.source !== undefined && item.source.length > 0 && (
                    <span className="host-memory-source">{item.source}</span>
                  )}
                  <span className="host-memory-time">{formatRelativeTime(item.createdAt, Date.now(), locale)}</span>
                </div>
              </article>
            ))}
            <div ref={sentinel} className="host-memory-sentinel" aria-hidden="true" />
            {memory.query === undefined && (
              <div className="host-memory-foot">
                {memory.hasMore ? (
                  <button className="quiet host-memory-more" type="button" disabled={memory.loading} onClick={() => onRequest({ cursor: lastId })}>
                    {t("memory.loadMore")}
                  </button>
                ) : (
                  <span className="host-memory-nomore">{t("memory.noMore")}</span>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </Screen>
  );
}
