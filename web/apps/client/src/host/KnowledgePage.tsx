import { useEffect, useRef, useState } from "react";
import type { HostKnowledgeBase } from "../api.js";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, TopBar } from "../components/primitives.js";
import { formatBytes, formatMonthDay, formatNumber } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { HostKnowledgeDocumentsState, HostKnowledgeState } from "./useHostConnection.js";

interface KnowledgePageProps {
  readonly knowledge: HostKnowledgeState;
  readonly documents: HostKnowledgeDocumentsState;
  /** 能力位 / 运行时「不支持」判断。 */
  readonly supported: boolean;
  readonly onRequestBases: () => void;
  readonly onRequestDocuments: (options: { readonly kbId: string; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => void;
  readonly onBack: () => void;
}

/**
 * 知识库页：知识库列表 → 某个库的文档列表（只读）。
 *
 * 本批不做导入/删除，页面底部明确写「在手机上导入与管理」。
 * 文档分页与记忆页同一套：游标是上一页最后一条 id，滚到底自动续，到底显示「没有更多了」。
 */
export function KnowledgePage(props: KnowledgePageProps) {
  const { t } = useI18n();
  const { knowledge, documents, supported, onRequestBases, onRequestDocuments, onBack } = props;
  const [selected, setSelected] = useState<HostKnowledgeBase | undefined>(undefined);

  useEffect(() => {
    if (supported) onRequestBases();
  }, [supported, onRequestBases]);

  const openBase = (base: HostKnowledgeBase) => {
    setSelected(base);
    onRequestDocuments({ kbId: base.id, reset: true });
  };

  if (selected !== undefined) {
    return (
      <KnowledgeDocumentsView
        base={selected}
        documents={documents}
        supported={supported}
        onRequestDocuments={onRequestDocuments}
        onBack={() => setSelected(undefined)}
      />
    );
  }

  return (
    <Screen fill topBar={<TopBar title={t("knowledge.title")} largeTitle onBack={onBack} />}>
      {!supported || knowledge.unsupported ? (
        <div className="muse-stack">
          <CardGroup>
            <ListRow title={t("knowledge.unsupported")} divider={false} />
          </CardGroup>
        </div>
      ) : knowledge.error !== undefined ? (
        <ErrorStateBox message={knowledge.error} onRetry={onRequestBases} />
      ) : knowledge.loading && knowledge.bases.length === 0 ? (
        <ListSkeleton label={t("knowledge.loading")} />
      ) : (
        <div className="muse-stack">
          <CardGroup>
            {knowledge.bases.length === 0 ? (
              <ListRow title={t("knowledge.empty")} divider={false} />
            ) : (
              knowledge.bases.map((base, index) => (
                <ListRow
                  key={base.id}
                  title={base.name}
                  subtitle={t("knowledge.counts", {
                    docs: formatNumber(base.documentCount, "en"),
                    chunks: formatNumber(base.chunkCount, "en"),
                  })}
                  trailing={
                    <span className="host-setting-value">
                      {base.embeddingBackend !== undefined && base.embeddingBackend.length > 0
                        ? base.embeddingBackend
                        : t("knowledge.embeddingNone")}
                    </span>
                  }
                  chevron
                  divider={index !== knowledge.bases.length - 1}
                  onClick={() => openBase(base)}
                />
              ))
            )}
          </CardGroup>
          <CardGroup>
            <ListRow title={t("knowledge.readOnly")} divider={false} />
          </CardGroup>
        </div>
      )}
    </Screen>
  );
}

/** 某个知识库的文档列表（单独导出：没有交互也能直接渲染断言）。 */
export function KnowledgeDocumentsView({
  base,
  documents,
  supported,
  onRequestDocuments,
  onBack,
}: {
  readonly base: HostKnowledgeBase;
  readonly documents: HostKnowledgeDocumentsState;
  readonly supported: boolean;
  readonly onRequestDocuments: (options: { readonly kbId: string; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => void;
  readonly onBack: () => void;
}) {
  const { t } = useI18n();
  const sentinel = useRef<HTMLDivElement | null>(null);
  const lastId = documents.items.length > 0 ? documents.items[documents.items.length - 1]?.id : undefined;
  const canLoadMore = documents.hasMore && !documents.loading;
  // 滚到底自动续页；环境没有 IntersectionObserver 时走下面的「加载更多」按钮。
  useEffect(() => {
    if (!canLoadMore || lastId === undefined) return;
    const element = sentinel.current;
    if (element === null || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) onRequestDocuments({ kbId: base.id, cursor: lastId });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [canLoadMore, lastId, base.id, onRequestDocuments]);
  return (
    <Screen fill topBar={<TopBar title={base.name} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        {!supported || documents.unsupported ? (
          <CardGroup>
            <ListRow title={t("knowledge.unsupported")} divider={false} />
          </CardGroup>
        ) : documents.error !== undefined ? (
          <ErrorStateBox message={documents.error} onRetry={() => onRequestDocuments({ kbId: base.id, reset: true })} />
        ) : documents.loading && documents.items.length === 0 ? (
          <ListSkeleton groups={1} rows={4} label={t("knowledge.documentsLoading")} />
        ) : documents.items.length === 0 ? (
          <CardGroup>
            <ListRow title={t("knowledge.documentsEmpty")} divider={false} />
          </CardGroup>
        ) : (
          <>
            <CardGroup title={t("knowledge.documents")}>
              {documents.items.map((document, index) => (
                <ListRow
                  key={document.id}
                  title={document.title}
                  subtitle={t("knowledge.documentMeta", {
                    chunks: formatNumber(document.chunkCount, "en"),
                    size: formatBytes(document.sizeBytes),
                  })}
                  trailing={
                    <span className="host-setting-value">
                      {document.status !== undefined && document.status.length > 0
                        ? document.status
                        : formatMonthDay(document.createdAt)}
                    </span>
                  }
                  divider={index !== documents.items.length - 1}
                />
              ))}
            </CardGroup>
            <div ref={sentinel} className="host-memory-sentinel" aria-hidden="true" />
            <div className="host-memory-foot">
              {documents.hasMore ? (
                <button
                  className="quiet host-memory-more"
                  type="button"
                  disabled={documents.loading}
                  onClick={() => onRequestDocuments({ kbId: base.id, cursor: lastId })}
                >
                  {t("common.loadMore")}
                </button>
              ) : (
                <span className="host-memory-nomore">{t("common.noMore")}</span>
              )}
            </div>
          </>
        )}
      </div>
    </Screen>
  );
}
