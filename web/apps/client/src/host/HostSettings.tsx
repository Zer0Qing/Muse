import { useCallback, useEffect, useState } from "react";
import type { HostAssistantDetail, HostAssistantSummary, HostProviderSummary, HostSettingsEntry, HostSettingsGroup, HostSettingsSnapshot } from "../api.js";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, SettingsIcon, Switch, TopBar } from "../components/primitives.js";
import type { HostCapabilities } from "./capabilities.js";
import { AssistantsPage } from "./AssistantsPage.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { MEMORY_FLAGS, KNOWLEDGE_FLAGS, TASK_FLAGS, SYSTEM_STATUS_FLAGS, platformAvailability, platformKeysForEntry, supportsFlag } from "./featureCapabilities.js";
import { HostPlaceholder } from "./HostPlaceholder.js";
import { MemoryPage } from "./MemoryPage.js";
import { KnowledgePage } from "./KnowledgePage.js";
import { TasksPage } from "./TasksPage.js";
import { DataPrivacyPage } from "./DataPrivacyPage.js";
import { PlatformPage } from "./PlatformPage.js";
import { pageForEntry, SETTINGS_PAGE_SPECS, type SettingsPageSpec } from "./settingsPages.js";
import { settingsPageTitle, type SettingsSearchHit } from "./settingsQuery.js";
import { SettingsSearch } from "./SettingsSearch.js";
import { SettingsValueCard, SettingsValuePage } from "./SettingsValuePage.js";
import { useDelayedFlag } from "./useDelayedFlag.js";
import type { HostKnowledgeDocumentsState, HostKnowledgeState, HostMemoryState, HostSettingsState, HostSystemState, HostTasksState } from "./useHostConnection.js";
import { valuesForPage } from "./settingsPages.js";

export interface HostSettingsProps {
  readonly settings: HostSettingsState;
  readonly capabilities: HostCapabilities;
  readonly onBack: () => void;
  readonly onRetry: () => void;
  readonly onToggle: (key: string, enabled: boolean) => void;
  /** 子页的通用写入（boolean / number / enum）。 */
  readonly onWrite: (key: string, value: string | number | boolean) => void;
  /** 助手与角色页 / 记忆页需要的数据与入口。 */
  readonly assistants: readonly HostAssistantSummary[];
  readonly providers: readonly HostProviderSummary[];
  readonly assistantDetails: Readonly<Record<string, HostAssistantDetail>>;
  readonly assistantDetailUnsupported: boolean;
  readonly memory: HostMemoryState;
  readonly capabilityFlags: readonly string[] | undefined;
  readonly onRequestAssistantDetail: (assistantId: string) => void;
  readonly onRequestProviders: () => void;
  readonly onRequestMemory: (options?: {
    readonly query?: string | undefined;
    readonly cursor?: string | undefined;
    readonly reset?: boolean | undefined;
  }) => void;
  readonly onStartSession: (assistantId: string) => void;
  /** 知识库与定时任务（只读页）。 */
  readonly knowledge: HostKnowledgeState;
  readonly knowledgeDocuments: HostKnowledgeDocumentsState;
  readonly tasks: HostTasksState;
  readonly onRequestKnowledge: () => void;
  readonly onRequestKnowledgeDocuments: (options: {
    readonly kbId: string;
    readonly cursor?: string | undefined;
    readonly reset?: boolean | undefined;
  }) => void;
  readonly onRequestTasks: () => void;
  /** 系统状态（数据与隐私页 / 平台专有页共用）。 */
  readonly system: HostSystemState;
  readonly onRequestSystemStatus: () => void;
}

/** 当前打开的子页：目录条目，或搜索结果里的某条值（附带要高亮的那一行）。 */
type OpenPage =
  | { readonly kind: "entry"; readonly entry: HostSettingsEntry }
  | { readonly kind: "value"; readonly page: SettingsPageSpec; readonly title: string; readonly focusKey: string };

/**
 * 设置主页。
 *
 * 分组、顺序、条目和开关状态全部来自 Host 的 `settings.snapshot`，前端不重排、
 * 不过滤、也不补条目；标题/说明/分组标题都是 Host 按当前语言解析好的文本，
 * 本端不保存任何文案（`titleKey` / `key` 只是稳定标识）。
 *
 * 顶部有一条吸顶搜索框：点开是全屏搜索，结果只用这份快照在本地过滤（不发请求），
 * 点结果直达对应子页；值行还会把它自己那一行高亮出来。
 */
export function HostSettings({
  settings,
  capabilities,
  onBack,
  onRetry,
  onToggle,
  onWrite,
  assistants,
  providers,
  assistantDetails,
  assistantDetailUnsupported,
  memory,
  capabilityFlags,
  onRequestAssistantDetail,
  onRequestProviders,
  onRequestMemory,
  onStartSession,
  knowledge,
  knowledgeDocuments,
  tasks,
  onRequestKnowledge,
  onRequestKnowledgeDocuments,
  onRequestTasks,
  system,
  onRequestSystemStatus,
}: HostSettingsProps) {
  const { t } = useI18n();
  const [openPage, setOpenPage] = useState<OpenPage | undefined>(undefined);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const snapshot = settings.snapshot;
  /** 记忆页是否能拉到数据：能力位缺位时直接给提示（老 Host 先试一次）。 */
  const supportsMemory = supportsFlag(capabilityFlags, MEMORY_FLAGS) && !memory.unsupported;
  // 读取很快时不上骨架，避免闪一下。
  const skeletonDue = useDelayedFlag(settings.loading && snapshot === undefined);

  const closeSearch = useCallback(() => {
    setSearching(false);
    setQuery("");
  }, []);

  // 快捷键：/ 或 Cmd/Ctrl+K 打开搜索。焦点在输入控件里时不抢（/ 是正常字符）。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (snapshot === undefined) return;
      const target = event.target instanceof HTMLElement ? event.target : null;
      const typing =
        target !== null && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
      const chord = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k";
      const slash = event.key === "/" && !typing && !event.metaKey && !event.ctrlKey && !event.altKey;
      if (!chord && !slash) return;
      event.preventDefault();
      setSearching(true);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [snapshot]);

  function handlePick(hit: SettingsSearchHit) {
    closeSearch();
    if (snapshot === undefined) return;
    const target = hit.target;
    if (target.kind === "entry") {
      const entry = findEntry(snapshot, target.entryId);
      if (entry !== undefined) setOpenPage({ kind: "entry", entry });
      return;
    }
    const page = SETTINGS_PAGE_SPECS.find((spec) => spec.id === target.pageId);
    if (page === undefined) return;
    setOpenPage({ kind: "value", page, title: settingsPageTitle(snapshot, page), focusKey: target.valueKey });
  }

  if (openPage !== undefined) {
    const back = () => setOpenPage(undefined);
    // 「助手与角色」和「记忆」是真实功能页，不是值表单。
    if (openPage.kind === "entry" && openPage.entry.id === "assistants") {
      return (
        <AssistantsPage
          assistants={assistants}
          providers={providers}
          details={assistantDetails}
          flags={capabilityFlags}
          loading={snapshot === undefined}
          detailUnsupported={assistantDetailUnsupported}
          onRequestDetail={onRequestAssistantDetail}
          onRequestProviders={onRequestProviders}
          onStartSession={onStartSession}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "memory") {
      const memoryPage = SETTINGS_PAGE_SPECS.find((spec) => spec.id === "memory");
      const hasValues = memoryPage !== undefined && snapshot !== undefined && valuesForPage(memoryPage, snapshot.values).length > 0;
      return (
        <MemoryPage memory={memory} supported={supportsMemory} onRequest={onRequestMemory} onBack={back}>
          {hasValues && memoryPage !== undefined && snapshot !== undefined && (
            <SettingsValueCard page={memoryPage} snapshot={snapshot} onWrite={onWrite} />
          )}
        </MemoryPage>
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "rag") {
      return (
        <KnowledgePage
          knowledge={knowledge}
          documents={knowledgeDocuments}
          supported={supportsFlag(capabilityFlags, KNOWLEDGE_FLAGS) && !knowledge.unsupported}
          onRequestBases={onRequestKnowledge}
          onRequestDocuments={onRequestKnowledgeDocuments}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "scheduled_tasks") {
      return (
        <TasksPage
          tasks={tasks}
          supported={supportsFlag(capabilityFlags, TASK_FLAGS) && !tasks.unsupported}
          onRequest={onRequestTasks}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "data_management") {
      return (
        <DataPrivacyPage
          title={openPage.entry.title}
          system={system}
          supported={supportsFlag(capabilityFlags, SYSTEM_STATUS_FLAGS) && !system.unsupported}
          onRequest={onRequestSystemStatus}
          pii={piiGuardState(snapshot)}
          onTogglePii={(enabled) => onToggle(PII_GUARD_KEY, enabled)}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry") {
      const platformKeys = platformKeysForEntry(openPage.entry.id);
      if (platformKeys !== undefined) {
        return (
          <PlatformPage
            title={openPage.entry.title}
            description={openPage.entry.desc}
            availability={platformAvailability(system.status?.capabilities, platformKeys)}
            onBack={back}
          />
        );
      }
    }
    if (openPage.kind === "value") {
      return (
        <SettingsValuePage
          page={openPage.page}
          title={openPage.title}
          snapshot={snapshot}
          loading={settings.loading}
          error={settings.error}
          capabilities={capabilities}
          focusKey={openPage.focusKey}
          onBack={back}
          onRetry={onRetry}
          onWrite={onWrite}
        />
      );
    }
    const page = pageForEntry(openPage.entry.id);
    if (page !== undefined) {
      return (
        <SettingsValuePage
          page={page}
          title={openPage.entry.title}
          snapshot={snapshot}
          loading={settings.loading}
          error={settings.error}
          capabilities={capabilities}
          onBack={back}
          onRetry={onRetry}
          onWrite={onWrite}
        />
      );
    }
    // 没做过的页面（含只能在手机上操作的能力）全部落到统一占位页。
    return <HostPlaceholder title={openPage.entry.title} description={openPage.entry.desc} onBack={back} />;
  }

  return (
    <Screen fill topBar={<TopBar title={t("settings.title")} largeTitle onBack={onBack} />}>
      {snapshot === undefined ? (
        settings.error !== undefined ? (
          <ErrorStateBox message={settings.error} onRetry={onRetry} />
        ) : skeletonDue ? (
          <ListSkeleton label={t("settings.loading")} />
        ) : (
          // 300ms 内先什么都不画（空白比闪一下骨架稳）。
          <div className="host-loading-quiet" role="status" aria-label={t("settings.loading")} />
        )
      ) : (
        <>
          <div className="host-settings-search">
            <button className="quiet host-search-trigger" type="button" aria-label={t("settings.search")} onClick={() => setSearching(true)}>
              <span className="host-search-trigger-text">{t("settings.search")}</span>
              <kbd className="host-search-kbd">/</kbd>
            </button>
          </div>
          <div className="muse-stack">
            {snapshot.groups.map((group) => (
              <SettingsGroup
                key={group.key}
                group={group}
                writableKeys={snapshot.writableKeys}
                onOpen={(entry) => setOpenPage({ kind: "entry", entry })}
                onToggle={onToggle}
              />
            ))}
          </div>
        </>
      )}
      {searching && snapshot !== undefined && (
        <SettingsSearch snapshot={snapshot} query={query} onQueryChange={setQuery} onPick={handlePick} onClose={closeSearch} />
      )}
    </Screen>
  );
}

/** PII 守护的开关键（与 Host 的 settings.update 白名单一致）。 */
const PII_GUARD_KEY = "piiGuardEnabled";

/** 从设置快照里读 PII 守护：状态来自条目，可写性来自 writableKeys。 */
function piiGuardState(snapshot: HostSettingsSnapshot | undefined): { readonly checked: boolean; readonly writable: boolean } | undefined {
  if (snapshot === undefined) return undefined;
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey !== PII_GUARD_KEY) continue;
      return { checked: entry.checked === true, writable: snapshot.writableKeys.includes(PII_GUARD_KEY) };
    }
  }
  return undefined;
}

function findEntry(snapshot: HostSettingsSnapshot, entryId: string): HostSettingsEntry | undefined {
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.id === entryId) return entry;
    }
  }
  return undefined;
}

interface SettingsGroupProps {
  readonly group: HostSettingsGroup;
  readonly writableKeys: readonly string[];
  readonly onOpen: (entry: HostSettingsEntry) => void;
  readonly onToggle: (key: string, enabled: boolean) => void;
}

function SettingsGroup({ group, writableKeys, onOpen, onToggle }: SettingsGroupProps) {
  const entries = group.entries;
  return (
    <CardGroup title={group.groupTitle}>
      {entries.map((entry, index) => (
        <SettingsRow
          key={entry.id}
          entry={entry}
          last={index === entries.length - 1}
          writableKeys={writableKeys}
          onOpen={onOpen}
          onToggle={onToggle}
        />
      ))}
    </CardGroup>
  );
}

interface SettingsRowProps {
  readonly entry: HostSettingsEntry;
  readonly last: boolean;
  readonly writableKeys: readonly string[];
  readonly onOpen: (entry: HostSettingsEntry) => void;
  readonly onToggle: (key: string, enabled: boolean) => void;
}

/**
 * 单个设置项。`kind` 决定右侧：link / action → 右箭头并进入子页；
 * switch → MuseSwitch（写入白名单外的键直接禁用，避免点了才被 Host 拒）。
 * 左侧是 iconKey 对应的圆形小图标（与手机同一套图形）。
 */
function SettingsRow({ entry, last, writableKeys, onOpen, onToggle }: SettingsRowProps) {
  const title = entry.title;
  const desc = entry.desc;
  const text = {
    title,
    ...(desc === undefined || desc === "" ? {} : { subtitle: desc }),
    leading: <SettingsIcon iconKey={entry.iconKey} />,
    divider: !last,
  };

  if (entry.kind === "switch") {
    const toggleKey = entry.toggleKey;
    const writable = toggleKey !== undefined && writableKeys.includes(toggleKey);
    return (
      <ListRow
        {...text}
        trailing={
          <Switch
            checked={entry.checked === true}
            label={title}
            disabled={!writable}
            onChange={(next) => {
              if (writable && toggleKey !== undefined) onToggle(toggleKey, next);
            }}
          />
        }
      />
    );
  }

  // 未知的 kind：降级成一行中性展示（不画箭头、不可点，也不抛错）。
  if (entry.kind !== "link" && entry.kind !== "action") {
    return <ListRow {...text} />;
  }

  // Host 标了 enabled = false 的入口（如实验功能关闭时的小手机）只展示，不进子页。
  if (entry.enabled === false) {
    return <ListRow {...text} />;
  }

  return <ListRow {...text} chevron onClick={() => onOpen(entry)} />;
}
