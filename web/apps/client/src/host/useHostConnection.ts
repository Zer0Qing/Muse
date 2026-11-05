import { useCallback, useEffect, useRef, useState } from "react";
import {
  connectHost,
  type HostAssistantDetail,
  type HostAssistantSummary,
  type HostCommand,
  type HostConnection,
  type HostConnectionStatus,
  type HostEvent,
  type HostKnowledgeBase,
  type HostKnowledgeDocument,
  type HostMemoryItem,
  type HostMessage,
  type HostProviderSummary,
  type HostRuntimeStats,
  type HostScheduledTask,
  type HostSettingsSnapshot,
  type HostSystemStatus,
} from "../api.js";
import { describeHostError } from "./hostErrors.js";
import { isUnsupportedCommandError } from "./featureCapabilities.js";
import { PENDING_TIMEOUT_MS, settlePolicyFor } from "./pendingTimeout.js";
import { mergeMessages } from "./messageEqual.js";
import { DEFAULT_LOCALE } from "../i18n/locale.js";
import type { MessageKey } from "../i18n/messages.js";
import { createTranslator, type Translate, type TranslateParams } from "../i18n/translate.js";
import { applySettingValue, settingValueOf } from "./settingsState.js";

export type HostSessionSummary = NonNullable<HostEvent["sessions"]>[number];
export type HostApproval = NonNullable<HostEvent["pendingApprovals"]>[number];

export interface HostSnapshot {
  readonly sessions: readonly HostSessionSummary[];
  readonly messages: readonly HostMessage[];
  readonly sessionId: string | undefined;
  readonly isStreaming: boolean;
  readonly approvals: readonly HostApproval[];
  /** Host 声明的能力位；老 Host 不下发，保持 undefined（不是空数组）。 */
  readonly capabilities: readonly string[] | undefined;
}

const EMPTY: HostSnapshot = { sessions: [], messages: [], sessionId: undefined, isStreaming: false, approvals: [], capabilities: undefined };

/** memory.list 每页条数（Host 默认 30、上限 100）。 */
const MEMORY_PAGE_SIZE = 30;
/** knowledge.documents 每页条数（Host 默认 20、上限 50）。 */
const KNOWLEDGE_PAGE_SIZE = 20;

/** 知识库列表的读取状态。 */
export interface HostKnowledgeState {
  readonly bases: readonly HostKnowledgeBase[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

/** 某个知识库的文档（分页累积）。 */
export interface HostKnowledgeDocumentsState {
  readonly kbId: string | undefined;
  readonly items: readonly HostKnowledgeDocument[];
  readonly hasMore: boolean;
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

/** 定时任务列表的读取状态。 */
export interface HostTasksState {
  readonly items: readonly HostScheduledTask[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

/** system.status 的读取状态。 */
export interface HostSystemState {
  readonly status: HostSystemStatus | undefined;
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

const EMPTY_SYSTEM: HostSystemState = { status: undefined, loading: false, error: undefined, unsupported: false };

/**
 * 「运行」页三条命令的读取状态。
 *
 * 三个 loading 各自独立：三个 Tab 各自刷新，共用一个开关会让「只看日志」把诊断和统计也拖进转圈。
 */
export interface HostRuntimeState {
  readonly logs: readonly string[] | undefined;
  readonly logsLoading: boolean;
  readonly diagnostics: string | undefined;
  readonly diagnosticsLoading: boolean;
  readonly stats: HostRuntimeStats | undefined;
  readonly statsLoading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

const EMPTY_RUNTIME: HostRuntimeState = {
  logs: undefined,
  logsLoading: false,
  diagnostics: undefined,
  diagnosticsLoading: false,
  stats: undefined,
  statsLoading: false,
  error: undefined,
  unsupported: false,
};

const EMPTY_KNOWLEDGE: HostKnowledgeState = { bases: [], loading: false, error: undefined, unsupported: false };
const EMPTY_DOCUMENTS: HostKnowledgeDocumentsState = {
  kbId: undefined,
  items: [],
  hasMore: false,
  loading: false,
  error: undefined,
  unsupported: false,
};
const EMPTY_TASKS: HostTasksState = { items: [], loading: false, error: undefined, unsupported: false };

/** 记忆页的数据：分页累积 + 当前是搜索还是列表。 */
export interface HostMemoryState {
  readonly items: readonly HostMemoryItem[];
  readonly hasMore: boolean;
  readonly loading: boolean;
  readonly error: string | undefined;
  /** 当前列表对应的搜索词；undefined 表示普通列表。 */
  readonly query: string | undefined;
  /** Host 回了「不支持这个命令」：页面切成提示态。 */
  readonly unsupported: boolean;
}

const EMPTY_MEMORY: HostMemoryState = {
  items: [],
  hasMore: false,
  loading: false,
  error: undefined,
  query: undefined,
  unsupported: false,
};

/** 设置主页的请求状态：一次 settings.snapshot 的结果 + 正在请求 / 失败原因。 */
export interface HostSettingsState {
  /** 首次读取成功后才存在；失败时保留上一份，不把列表闪没。 */
  readonly snapshot?: HostSettingsSnapshot;
  readonly loading: boolean;
  /** 只在读取失败时填；写开关失败走 toast，不抢页面的错误位。 */
  readonly error?: string;
}

const EMPTY_SETTINGS: HostSettingsState = { loading: false };

function settingsWithError(current: HostSettingsState, message: string): HostSettingsState {
  return { ...(current.snapshot === undefined ? {} : { snapshot: current.snapshot }), loading: false, error: message };
}

function settingsLoading(current: HostSettingsState): HostSettingsState {
  return { ...(current.snapshot === undefined ? {} : { snapshot: current.snapshot }), loading: true };
}

export interface UseHostConnectionOptions {
  /** Surfaced as a toast; command failures must never be silent. */
  readonly onError: (message: string) => void;
  /** 快照落地时回调（只用来记 perf 计时，不参与渲染）。 */
  readonly onSnapshot?: (() => void) | undefined;
  /** 当前语言的翻译函数：错误文案与会话预览前缀要跟着界面语言走。 */
  readonly translate?: Translate | undefined;
}

export interface HostConnectionApi {
  readonly status: HostConnectionStatus;
  /** 已发起的重连次数（连接成功后归零），界面用它显示「第 N 次」。 */
  readonly reconnectAttempt: number;
  /** False until the first state.snapshot arrives (avoids empty-state flicker). */
  readonly ready: boolean;
  readonly snapshot: HostSnapshot;
  /** Last-message preview per session id (only the part the snapshot does not carry). */
  readonly previews: Readonly<Record<string, string>>;
  /** assistants.list 的结果：新建会话选助手、顶栏显示当前助手都用它。 */
  readonly assistants: readonly HostAssistantSummary[];
  /** providers.list 的结果：把 modelId 翻成可读的模型名。 */
  readonly providers: readonly HostProviderSummary[];
  /** settings.snapshot 的结果与请求状态（设置主页用）。 */
  readonly settings: HostSettingsState;
  readonly send: (command: Omit<HostCommand, "requestId">) => void;
  /**
   * 乐观写：先发命令，Host 回 error 时执行 `onReject` 回滚并把原因交给 toast。
   * `onAccepted` 拿得到 `command.accepted` 的载荷（如 message.delete 的 skippedMessageIds）。
   */
  readonly sendOptimistic: (
    command: Omit<HostCommand, "requestId">,
    onReject: () => void,
    onAccepted?: (event: HostEvent) => void,
  ) => void;
  readonly requestPreviews: (sessionIds: readonly string[]) => void;
  readonly requestSettings: () => void;
  readonly requestAssistants: () => void;
  readonly requestProviders: () => void;
  /** 助手详情缓存（按 id）与「这个 Host 不支持详情」的标记。 */
  readonly assistantDetails: Readonly<Record<string, HostAssistantDetail>>;
  readonly assistantDetailUnsupported: boolean;
  readonly requestAssistantDetail: (assistantId: string) => void;
  /** 记忆列表（分页累积）与请求入口。 */
  readonly memory: HostMemoryState;
  readonly requestMemory: (options?: { readonly query?: string | undefined; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => void;
  /** 知识库（列表 + 文档分页）与定时任务，全部只读。 */
  readonly knowledge: HostKnowledgeState;
  readonly knowledgeDocuments: HostKnowledgeDocumentsState;
  readonly tasks: HostTasksState;
  readonly requestKnowledge: () => void;
  readonly requestKnowledgeDocuments: (options: { readonly kbId: string; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => void;
  readonly requestTasks: () => void;
  /** 系统状态（存储/备份/平台能力）。 */
  readonly system: HostSystemState;
  readonly requestSystemStatus: () => void;
  /** 「运行」页：日志尾部 / 诊断快照 / 窗口统计，全部只读。 */
  readonly runtime: HostRuntimeState;
  readonly requestRuntimeLogs: (limit?: number) => void;
  readonly requestRuntimeDiagnostics: () => void;
  readonly requestRuntimeStats: () => void;
  /** 乐观改开关并发 settings.update；被 Host 拒绝时自动回滚。 */
  readonly updateSetting: (key: string, enabled: boolean) => void;
  /** 子页的通用写入：boolean / number / enum 文本统一走这里，失败原因交给 toast。 */
  readonly updateSettingValue: (key: string, value: string | number | boolean) => void;
  readonly retry: () => void;
}

function previewText(message: HostMessage, t: Translate): string {
  const speakerKey =
    message.role === "user"
      ? "preview.you"
      : message.role === "tool"
        ? "preview.tool"
        : message.role === "system"
          ? "preview.system"
          : "preview.assistant";
  const flat = message.content.replace(/\s+/g, " ").trim();
  if (!flat) return "";
  const clipped = flat.length > 72 ? `${flat.slice(0, 72)}…` : flat;
  return t("preview.line", { speaker: t(speakerKey), text: clipped });
}
/**
 * Owns one Host WebSocket for the whole mobile shell: snapshot state, reconnect
 * status, command dispatch and lazy session previews.
 */
export function useHostConnection(options: UseHostConnectionOptions): HostConnectionApi {
  const [status, setStatus] = useState<HostConnectionStatus>("reconnecting");
  const [reconnectAttempt, setReconnectAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<HostSnapshot>(EMPTY);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [assistants, setAssistants] = useState<readonly HostAssistantSummary[]>([]);
  const [providers, setProviders] = useState<readonly HostProviderSummary[]>([]);
  const [assistantDetails, setAssistantDetails] = useState<Record<string, HostAssistantDetail>>({});
  const [assistantDetailUnsupported, setAssistantDetailUnsupported] = useState(false);
  const [memory, setMemory] = useState<HostMemoryState>(EMPTY_MEMORY);
  const [knowledge, setKnowledge] = useState<HostKnowledgeState>(EMPTY_KNOWLEDGE);
  const [knowledgeDocuments, setKnowledgeDocuments] = useState<HostKnowledgeDocumentsState>(EMPTY_DOCUMENTS);
  const [tasks, setTasks] = useState<HostTasksState>(EMPTY_TASKS);
  const [system, setSystem] = useState<HostSystemState>(EMPTY_SYSTEM);
  const [runtime, setRuntime] = useState<HostRuntimeState>(EMPTY_RUNTIME);
  const [settings, setSettings] = useState<HostSettingsState>(EMPTY_SETTINGS);
  const [ready, setReady] = useState(false);
  const [retryToken, setRetryToken] = useState(0);
  const connectionRef = useRef<HostConnection | undefined>(undefined);
  const pendingPreviews = useRef(new Map<string, string>());
  /** 记忆分页/搜索的待回请求：requestId → 本次是重置还是追加。 */
  const pendingMemory = useRef(new Map<string, { readonly reset: boolean; readonly query?: string | undefined }>());
  /** 助手详情的待回请求：requestId → assistantId。 */
  const pendingDetails = useRef(new Map<string, string>());
  /** 知识库 / 文档 / 定时任务 / 系统状态 / 运行三条命令的待回请求：requestId → 用途。 */
  const pendingQueries = useRef(
    new Map<string, {
      readonly kind:
        | "knowledge"
        | "documents"
        | "tasks"
        | "system"
        | "runtimeLogs"
        | "runtimeDiagnostics"
        | "runtimeStats";
      readonly reset: boolean;
    }>(),
  );
  const pendingSettings = useRef(new Set<string>());
  const pendingWrites = useRef(new Map<string, { key: string; previous: unknown }>());
  /** 乐观消息写的回滚动作：requestId → 撤销函数。 */
  const pendingRollbacks = useRef(new Map<string, () => void>());
  /** 乐观消息写的落地回调：requestId → 处理 command.accepted 载荷。 */
  const pendingAccepts = useRef(new Map<string, (event: HostEvent) => void>());
  const optionsRef = useRef(options);
  // 当前语言的翻译器：错误文案跟着界面语言走；调用方没传就按 zh。
  const translateRef = useRef<Translate>(options.translate ?? createTranslator(DEFAULT_LOCALE));
  translateRef.current = options.translate ?? createTranslator(DEFAULT_LOCALE);
  const tt = useCallback(
    (key: MessageKey, params?: TranslateParams) => translateRef.current(key, params),
    [],
  );

  /** 按 requestId 挂的超时定时器；收到任何带该 id 的事件就撤掉。 */
  const pendingTimers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  const clearPendingTimeout = useCallback((requestId: string) => {
    const timer = pendingTimers.current.get(requestId);
    if (timer !== undefined) {
      clearTimeout(timer);
      pendingTimers.current.delete(requestId);
    }
  }, []);

  /**
   * 到点结算。读与写必须不同，判据取自 pendingTimeout（可单测）：
   *  - 读：停掉转圈、给一句「没有回应」，用户可以直接重试；
   *  - 写：**不回滚、不丢待办**。命令可能已经执行成功，回滚等于向用户宣布「没保存上」，
   *    而真相是不知道；留着待办才能让迟到的 error 事件仍然把乐观写撤掉。
   */
  const settlePendingTimeout = useCallback(
    (requestId: string) => {
      const isWrite = pendingWrites.current.has(requestId) || pendingRollbacks.current.has(requestId);
      const policy = settlePolicyFor(isWrite ? "write" : "read");
      if (policy.messageKey === "error.writeUnconfirmed") {
        optionsRef.current.onError(tt("error.writeUnconfirmed"));
        return;
      }
      const message = tt("error.noResponse");
      const query = pendingQueries.current.get(requestId);
      if (query !== undefined) {
        pendingQueries.current.delete(requestId);
        if (query.kind === "knowledge") setKnowledge((current) => ({ ...current, loading: false, error: message }));
        else if (query.kind === "documents")
          setKnowledgeDocuments((current) => ({ ...current, loading: false, error: message }));
        else if (query.kind === "tasks") setTasks((current) => ({ ...current, loading: false, error: message }));
        else if (query.kind === "runtimeLogs") setRuntime((current) => ({ ...current, logsLoading: false, error: message }));
        else if (query.kind === "runtimeDiagnostics")
          setRuntime((current) => ({ ...current, diagnosticsLoading: false, error: message }));
        else if (query.kind === "runtimeStats") setRuntime((current) => ({ ...current, statsLoading: false, error: message }));
        else setSystem((current) => ({ ...current, loading: false, error: message }));
        return;
      }
      if (pendingMemory.current.has(requestId)) {
        pendingMemory.current.delete(requestId);
        setMemory((current) => ({ ...current, loading: false, error: message }));
        return;
      }
      pendingDetails.current.delete(requestId);
      pendingPreviews.current.delete(requestId);
      pendingSettings.current.delete(requestId);
    },
    [tt],
  );

  const armPendingTimeout = useCallback(
    (requestId: string) => {
      clearPendingTimeout(requestId);
      pendingTimers.current.set(
        requestId,
        setTimeout(() => {
          pendingTimers.current.delete(requestId);
          settlePendingTimeout(requestId);
        }, PENDING_TIMEOUT_MS),
      );
    },
    [clearPendingTimeout, settlePendingTimeout],
  );
  // 读「最新设置」用：开关写入前要先知道上一份值才能回滚。
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  useEffect(() => {
    optionsRef.current = options;
  }, [options]);

  useEffect(() => {
    let active = true;
    let unsubscribeEvents: (() => void) | undefined;
    let unsubscribeStatus: (() => void) | undefined;
    setStatus("reconnecting");
    // 重连/重试后旧的设置快照作废，下一页读到的必须是新连接的数据。
    setSettings(EMPTY_SETTINGS);

    void connectHost()
      .then((connection) => {
        if (!active) {
          connection.close();
          return;
        }
        connectionRef.current = connection;
        unsubscribeStatus = connection.onStatus((next, tries) => {
          if (!active) return;
          setStatus(next);
          setReconnectAttempt(tries);
        });
        unsubscribeEvents = connection.subscribe((event) => {
          if (!active) return;
          if (event.requestId !== undefined) clearPendingTimeout(event.requestId);
          if (event.type === "state.snapshot") {
            setReady(true);
            // 合并：没变的消息复用旧对象，memo 才能真正生效（见 messageEqual.ts）。
            setSnapshot((current) => ({
              sessions: event.sessions ?? [],
              messages: mergeMessages(current.messages, event.messages ?? []),
              sessionId: event.sessionId,
              isStreaming: event.isStreaming === true,
              approvals: event.pendingApprovals ?? [],
              capabilities: event.capabilityFlags,
            }));
            optionsRef.current.onSnapshot?.();
            return;
          }
          if (event.type === "history.page") {
            const requestId = event.requestId;
            const target = requestId === undefined ? undefined : pendingPreviews.current.get(requestId);
            if (requestId === undefined || target === undefined) return;
            pendingPreviews.current.delete(requestId);
            const messages = event.messages ?? [];
            const latest = messages[messages.length - 1];
            const text = latest ? previewText(latest, tt) : "";
            if (!text) return;
            setPreviews((current) => (current[target] === text ? current : { ...current, [target]: text }));
            return;
          }
          if (event.type === "settings.snapshot") {
            if (event.requestId !== undefined) pendingSettings.current.delete(event.requestId);
            const next = event.settings;
            if (next === undefined) {
              setSettings((current) => settingsWithError(current, tt("error.settingsEmpty")));
              return;
            }
            setSettings({ snapshot: next, loading: false });
            return;
          }
          if (event.type === "assistants.list") {
            setAssistants(event.assistants ?? []);
            return;
          }
          if (event.type === "providers.list") {
            setProviders(event.providers ?? []);
            return;
          }
          if (event.type === "assistants.detail") {
            const requestId = event.requestId;
            const assistantId = requestId === undefined ? undefined : pendingDetails.current.get(requestId);
            if (requestId !== undefined) pendingDetails.current.delete(requestId);
            const detail = event.assistantDetail;
            if (assistantId !== undefined && detail !== undefined) {
              setAssistantDetails((current) => ({ ...current, [assistantId]: detail }));
            }
            return;
          }
          if (event.type === "knowledge.list") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setKnowledge((current) => ({ bases: event.bases ?? [], loading: false, error: undefined, unsupported: current.unsupported }));
            return;
          }
          if (event.type === "knowledge.documents") {
            const requestId = event.requestId;
            const pending = requestId === undefined ? undefined : pendingQueries.current.get(requestId);
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setKnowledgeDocuments((current) => {
              const base = pending?.reset === true ? [] : current.items;
              const seen = new Set<string>();
              const items = [...base, ...(event.documents ?? [])].filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
              return { ...current, items, hasMore: event.hasMore === true, loading: false, error: undefined };
            });
            return;
          }
          if (event.type === "system.status") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setSystem((current) => ({ status: event.system, loading: false, error: undefined, unsupported: current.unsupported }));
            return;
          }
          if (event.type === "runtime.logs") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setRuntime((current) => ({ ...current, logs: event.logs ?? [], logsLoading: false, error: undefined }));
            return;
          }
          if (event.type === "runtime.diagnostics") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            // 载荷缺失 = Host 采不出快照，保持「没有诊断」而不是编一个空串出来
            setRuntime((current) => ({ ...current, diagnostics: event.diagnostics, diagnosticsLoading: false, error: undefined }));
            return;
          }
          if (event.type === "runtime.stats") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setRuntime((current) => ({ ...current, stats: event.stats, statsLoading: false, error: undefined }));
            return;
          }
          if (event.type === "tasks.list") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setTasks((current) => ({ items: event.tasks ?? [], loading: false, error: undefined, unsupported: current.unsupported }));
            return;
          }
          if (event.type === "memory.list" || event.type === "memory.search") {
            const requestId = event.requestId;
            const pending = requestId === undefined ? undefined : pendingMemory.current.get(requestId);
            if (requestId !== undefined) pendingMemory.current.delete(requestId);
            const page = event.items ?? [];
            setMemory((current) => {
              const base = pending?.reset === true ? [] : current.items;
              const seen = new Set<string>();
              const items = [...base, ...page].filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
              return {
                items,
                hasMore: event.hasMore === true,
                loading: false,
                error: undefined,
                query: pending?.query ?? current.query,
                unsupported: current.unsupported,
              };
            });
            return;
          }
          if (event.type === "command.accepted") {
            // settings.update / 消息写落地。写失败会走 error 事件，这里只需清待办。
            const accepted = event.requestId === undefined ? undefined : pendingAccepts.current.get(event.requestId);
            if (event.requestId !== undefined) {
              pendingWrites.current.delete(event.requestId);
              pendingRollbacks.current.delete(event.requestId);
              pendingAccepts.current.delete(event.requestId);
            }
            if (accepted !== undefined) accepted(event);
            return;
          }
          if (event.type === "command.duplicate") {
            // 服务端认得这个 requestId 已经执行过，**不再重复执行**。
            // 客户端在这里要的是「结算」而不是「重放」：
            //  清掉所有按 requestId 挂着的待办，让按钮/开关不再等；
            //  但绝不能再调 accepted 的回调 —— 乐观写第一次就落地了，
            //  重放会把同一份改动二次应用（比如把消息再插一遍）。
            const requestId = event.requestId;
            if (requestId !== undefined) {
              pendingPreviews.current.delete(requestId);
              pendingSettings.current.delete(requestId);
              pendingWrites.current.delete(requestId);
              pendingRollbacks.current.delete(requestId);
              pendingAccepts.current.delete(requestId);
              pendingQueries.current.delete(requestId);
              pendingMemory.current.delete(requestId);
              pendingDetails.current.delete(requestId);
            }
            return;
          }
          if (event.type === "error") {
            const requestId = event.requestId;
            // 记忆 / 助手详情：失败就地显示，没实现就切成「暂不支持」提示，不弹 toast。
            const queryPending = requestId === undefined ? undefined : pendingQueries.current.get(requestId);
            if (requestId !== undefined && queryPending !== undefined) {
              pendingQueries.current.delete(requestId);
              const unsupported = isUnsupportedCommandError(event.error);
              const message = unsupported ? undefined : describeHostError(event.error, tt("error.operationFailed"), tt);
              if (queryPending.kind === "knowledge") {
                setKnowledge((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else if (queryPending.kind === "documents") {
                setKnowledgeDocuments((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else if (queryPending.kind === "tasks") {
                setTasks((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else if (queryPending.kind === "runtimeLogs") {
                setRuntime((current) => ({ ...current, logsLoading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else if (queryPending.kind === "runtimeDiagnostics") {
                setRuntime((current) => ({
                  ...current,
                  diagnosticsLoading: false,
                  error: message,
                  unsupported: current.unsupported || unsupported,
                }));
              } else if (queryPending.kind === "runtimeStats") {
                setRuntime((current) => ({ ...current, statsLoading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else {
                setSystem((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              }
              return;
            }
            const memoryPending = requestId === undefined ? undefined : pendingMemory.current.get(requestId);
            if (requestId !== undefined && memoryPending !== undefined) {
              pendingMemory.current.delete(requestId);
              const unsupported = isUnsupportedCommandError(event.error);
              setMemory((current) => ({
                ...current,
                loading: false,
                error: unsupported ? undefined : describeHostError(event.error, tt("error.operationFailed"), tt),
                unsupported: current.unsupported || unsupported,
              }));
              return;
            }
            const detailPending = requestId === undefined ? undefined : pendingDetails.current.get(requestId);            if (requestId !== undefined && detailPending !== undefined) {
              pendingDetails.current.delete(requestId);
              if (isUnsupportedCommandError(event.error)) {
                setAssistantDetailUnsupported(true);
              } else {
                optionsRef.current.onError(describeHostError(event.error, tt("error.operationFailed"), tt));
              }
              return;
            }
            // Preview lookups are best effort: an older Host without history.page
            // must not spam the user with toasts.
            if (requestId !== undefined && pendingPreviews.current.delete(requestId)) return;
            // 设置读取失败：交给页面的错误条 + 重试，不再叠一条 toast。
            if (requestId !== undefined && pendingSettings.current.delete(requestId)) {
              setSettings((current) => settingsWithError(current, describeHostError(event.error, tt("error.settingsRead"), tt)));
              return;
            }
            // 写入失败（开关 / 数字 / 枚举都走这里）：回滚到写入前的值，并把 Host 给的原因说出来。
            const rejected = requestId === undefined ? undefined : pendingWrites.current.get(requestId);
            if (requestId !== undefined && rejected !== undefined) {
              pendingWrites.current.delete(requestId);
              setSettings((current) => {
                const currentSnapshot = current.snapshot;
                return currentSnapshot === undefined
                  ? current
                  : { ...current, snapshot: applySettingValue(currentSnapshot, rejected.key, rejected.previous) };
              });
              optionsRef.current.onError(describeHostError(event.error, tt("error.settingsSave"), tt));
              return;
            }
            // 消息写（删除 / 收藏）被拒：先回滚本地，再把 Host 给的原因说出来。
            const rollback = requestId === undefined ? undefined : pendingRollbacks.current.get(requestId);
            if (requestId !== undefined && rollback !== undefined) {
              pendingRollbacks.current.delete(requestId);
              pendingAccepts.current.delete(requestId);
              rollback();
              optionsRef.current.onError(describeHostError(event.error, tt("error.operationFailed"), tt));
              return;
            }
            optionsRef.current.onError(describeHostError(event.error, tt("error.commandFailed"), tt));
          }
        });
        try {
          connection.send({ type: "hello", requestId: crypto.randomUUID() });
        } catch {
          // Status listener reports the real state; nothing to do here.
        }
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setStatus("closed");
        optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.connectFailed"));
      });

    return () => {
      active = false;
      unsubscribeEvents?.();
      unsubscribeStatus?.();
      // 断开/重连要把**所有**待办清掉，并把还在转圈的只读切片停下来。
      // 以前这里只清 5 个映射，漏了 pendingQueries / pendingMemory / pendingDetails，
      // 而且没有任何地方复位 loading：连接一断，知识库/文档/任务/系统状态/记忆页
      // 就永远停在「加载中」，用户只能整页刷新才能再看到数据。
      pendingTimers.current.forEach((timer) => clearTimeout(timer));
      pendingTimers.current.clear();
      pendingPreviews.current.clear();
      pendingSettings.current.clear();
      pendingWrites.current.clear();
      pendingRollbacks.current.clear();
      pendingAccepts.current.clear();
      pendingQueries.current.clear();
      pendingMemory.current.clear();
      pendingDetails.current.clear();
      setMemory((current) => ({ ...current, loading: false }));
      setKnowledge((current) => ({ ...current, loading: false }));
      setKnowledgeDocuments((current) => ({ ...current, loading: false }));
      setTasks((current) => ({ ...current, loading: false }));
      setSystem((current) => ({ ...current, loading: false }));
      setRuntime((current) => ({ ...current, logsLoading: false, diagnosticsLoading: false, statsLoading: false }));
      setSettings((current) => ({ ...current, loading: false }));
      connectionRef.current?.close();
      connectionRef.current = undefined;
    };
  }, [retryToken]);

  const send = useCallback((command: Omit<HostCommand, "requestId">) => {
    const connection = connectionRef.current;
    if (!connection) {
      optionsRef.current.onError(tt("error.notConnected"));
      return;
    }
    try {
      connection.send({ ...command, requestId: crypto.randomUUID() });
    } catch (cause) {
      optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.sendFailed"));
    }
  }, []);

  const sendOptimistic = useCallback(
    (command: Omit<HostCommand, "requestId">, onReject: () => void, onAccepted?: (event: HostEvent) => void) => {
      const connection = connectionRef.current;
      if (!connection) {
        onReject();
        optionsRef.current.onError(tt("error.notConnected"));
        return;
      }
      const requestId = crypto.randomUUID();
      pendingRollbacks.current.set(requestId, onReject);
      if (onAccepted !== undefined) pendingAccepts.current.set(requestId, onAccepted);
      // 两次登记都落地后再上表：这个函数里 requestId 的结局由 accepted / error / duplicate 决定，
      // 三条都没来（服务端卡死、被系统杀掉、协议分支没走到）就必须由超时收口。
      armPendingTimeout(requestId);
      try {
        connection.send({ ...command, requestId });
      } catch (cause) {
        pendingRollbacks.current.delete(requestId);
        pendingAccepts.current.delete(requestId);
        onReject();
        optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.sendFailed"));
      }
    },
    [],
  );

  const requestPreviews = useCallback((sessionIds: readonly string[]) => {
    const connection = connectionRef.current;
    if (!connection) return;
    for (const sessionId of sessionIds) {
      const requestId = crypto.randomUUID();
      pendingPreviews.current.set(requestId, sessionId);
      armPendingTimeout(requestId);
      try {
        connection.send({ type: "history.page", sessionId, limit: 1, offset: 0, requestId });
      } catch {
        pendingPreviews.current.delete(requestId);
      }
    }
  }, []);

  const retry = useCallback(() => setRetryToken((token) => token + 1), []);

  /** 拉一次助手列表：新建会话选助手、顶栏识别当前助手都靠它。 */
  const requestAssistants = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    try {
      connection.send({ type: "assistants.list", requestId: crypto.randomUUID() });
    } catch {
      // 读失败不报错：助手名只是展示用的，不影响聊天。
    }
  }, []);

  /** 供应商/模型名：只用于把 modelId 显示成全名。 */
  const requestProviders = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    try {
      connection.send({ type: "providers.list", requestId: crypto.randomUUID() });
    } catch {
      // 同上，纯展示。
    }
  }, []);

  /** 助手详情：按 id 缓存，重复进去不再请求。 */
  const requestAssistantDetail = useCallback((assistantId: string) => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingDetails.current.set(requestId, assistantId);
    armPendingTimeout(requestId);
    try {
      connection.send({ type: "assistants.detail", assistantId, requestId });
    } catch {
      pendingDetails.current.delete(requestId);
    }
  }, []);

  /**
   * 记忆：带 query 走 memory.search，否则 memory.list；cursor 是上一页最后一条 id。
   * 搜索不分页（契约里 memory.search 只接 query + limit）。
   */
  const requestMemory = useCallback(
    (options?: { readonly query?: string | undefined; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => {
      const connection = connectionRef.current;
      if (!connection) return;
      const query = options?.query !== undefined && options.query.length > 0 ? options.query : undefined;
      const requestId = crypto.randomUUID();
      const reset = options?.reset === true || options?.cursor === undefined;
      pendingMemory.current.set(requestId, { reset, query });
      armPendingTimeout(requestId);
      setMemory((current) => ({
        ...current,
        loading: true,
        error: undefined,
        ...(reset ? { items: [], hasMore: false } : {}),
      }));
      try {
        if (query !== undefined) connection.send({ type: "memory.search", query, limit: MEMORY_PAGE_SIZE, requestId });
        else connection.send({ type: "memory.list", limit: MEMORY_PAGE_SIZE, ...(options?.cursor === undefined ? {} : { cursor: options.cursor }), requestId });
      } catch (cause) {
        pendingMemory.current.delete(requestId);
        setMemory((current) => ({
          ...current,
          loading: false,
          error: cause instanceof Error ? cause.message : tt("error.sendFailed"),
        }));
      }
    },
    [tt],
  );

  /** 知识库列表（只读）。 */
  const requestKnowledge = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "knowledge", reset: true });
    armPendingTimeout(requestId);
    setKnowledge((current) => ({ ...current, loading: true, error: undefined }));
    try {
      connection.send({ type: "knowledge.list", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setKnowledge((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  /** 某个知识库的文档：cursor 是上一页最后一条 id（默认每页 20，Host 上限 50）。 */
  const requestKnowledgeDocuments = useCallback(
    (options: { readonly kbId: string; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => {
      const connection = connectionRef.current;
      if (!connection) return;
      const requestId = crypto.randomUUID();
      const reset = options.reset === true || options.cursor === undefined;
      pendingQueries.current.set(requestId, { kind: "documents", reset });
      armPendingTimeout(requestId);
      setKnowledgeDocuments((current) => ({
        ...current,
        kbId: options.kbId,
        loading: true,
        error: undefined,
        ...(reset ? { items: [], hasMore: false } : {}),
      }));
      try {
        connection.send({
          type: "knowledge.documents",
          kbId: options.kbId,
          limit: KNOWLEDGE_PAGE_SIZE,
          ...(options.cursor === undefined ? {} : { cursor: options.cursor }),
          requestId,
        });
      } catch (cause) {
        pendingQueries.current.delete(requestId);
        setKnowledgeDocuments((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
      }
    },
    [tt],
  );

  /** 定时任务列表（只读）。 */
  const requestTasks = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "tasks", reset: true });
    armPendingTimeout(requestId);
    setTasks((current) => ({ ...current, loading: true, error: undefined }));
    try {
      connection.send({ type: "tasks.list", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setTasks((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  /** 系统状态（存储 / 备份 / 平台能力），只读。 */
  const requestSystemStatus = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "system", reset: true });
    armPendingTimeout(requestId);
    setSystem((current) => ({ ...current, loading: true, error: undefined }));
    try {
      connection.send({ type: "system.status", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setSystem((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  /**
   * 日志尾部（默认 200 行、上限 500，由 Host 夹）。
   * 单行截断也归 Host 做：网页端拿到的是已经定好口径的行，不再各自裁一遍。
   */
  const requestRuntimeLogs = useCallback(
    (limit?: number) => {
      const connection = connectionRef.current;
      if (!connection) return;
      const requestId = crypto.randomUUID();
      pendingQueries.current.set(requestId, { kind: "runtimeLogs", reset: true });
      armPendingTimeout(requestId);
      setRuntime((current) => ({ ...current, logsLoading: true, error: undefined }));
      try {
        connection.send({ type: "runtime.logs", requestId, ...(limit === undefined ? {} : { limit }) });
      } catch (cause) {
        pendingQueries.current.delete(requestId);
        setRuntime((current) => ({ ...current, logsLoading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
      }
    },
    [tt],
  );

  /** 诊断快照的脱敏 JSON；与 App 诊断卡同一份导出契约。 */
  const requestRuntimeDiagnostics = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "runtimeDiagnostics", reset: true });
    armPendingTimeout(requestId);
    setRuntime((current) => ({ ...current, diagnosticsLoading: true, error: undefined }));
    try {
      connection.send({ type: "runtime.diagnostics", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setRuntime((current) => ({
        ...current,
        diagnosticsLoading: false,
        error: cause instanceof Error ? cause.message : tt("error.sendFailed"),
      }));
    }
  }, [tt]);

  /** 窗口统计：Web Host 延迟 p95 + 主线程卡顿三档计数。 */
  const requestRuntimeStats = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "runtimeStats", reset: true });
    armPendingTimeout(requestId);
    setRuntime((current) => ({ ...current, statsLoading: true, error: undefined }));
    try {
      connection.send({ type: "runtime.stats", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setRuntime((current) => ({ ...current, statsLoading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  const requestSettings = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) {
      setSettings((current) => settingsWithError(current, tt("error.notConnected")));
      return;
    }
    const requestId = crypto.randomUUID();
    pendingSettings.current.add(requestId);
    armPendingTimeout(requestId);
    setSettings(settingsLoading);
    try {
      connection.send({ type: "settings.snapshot", requestId });
    } catch (cause) {
      pendingSettings.current.delete(requestId);
      setSettings((current) => settingsWithError(current, cause instanceof Error ? cause.message : tt("error.settingsRead")));
    }
  }, []);

  /**
   * 设置写入：本地先改（乐观），Host 拒绝时回滚。
   *
   * 布尔仍以 `"true"` / `"false"` 字符串下发——Host 现有的 settings.update 就是按这两个字面量
   * 解析布尔的；数字与枚举直接给原类型，由 Host 按该键声明的类型校验。
   */
  const updateSettingValue = useCallback((key: string, value: string | number | boolean) => {
    const connection = connectionRef.current;
    if (!connection) {
      optionsRef.current.onError(tt("error.notConnected"));
      return;
    }
    const current = settingsRef.current.snapshot;
    if (current === undefined) return;
    const previous = settingValueOf(current, key);
    const requestId = crypto.randomUUID();
    pendingWrites.current.set(requestId, { key, previous });
    armPendingTimeout(requestId);
    setSettings((state) => (state.snapshot === undefined ? state : { ...state, snapshot: applySettingValue(state.snapshot, key, value) }));
    try {
      connection.send({ type: "settings.update", key, value: typeof value === "boolean" ? (value ? "true" : "false") : value, requestId });
    } catch (cause) {
      pendingWrites.current.delete(requestId);
      setSettings((state) => (state.snapshot === undefined ? state : { ...state, snapshot: applySettingValue(state.snapshot, key, previous) }));
      optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.settingsSave"));
    }
  }, []);

  /** 主页的开关行沿用旧入口，内部走同一套乐观更新 + 回滚。 */
  const updateSetting = useCallback(
    (key: string, enabled: boolean) => updateSettingValue(key, enabled),
    [updateSettingValue],
  );

  return {
    status,
    reconnectAttempt,
    ready,
    snapshot,
    previews,
    assistants,
    providers,
    assistantDetails,
    assistantDetailUnsupported,
    memory,
    knowledge,
    knowledgeDocuments,
    tasks,
    system,
    runtime,
    settings,
    send,
    sendOptimistic,
    requestPreviews,
    requestAssistants,
    requestProviders,
    requestAssistantDetail,
    requestMemory,
    requestKnowledge,
    requestKnowledgeDocuments,
    requestTasks,
    requestSystemStatus,
    requestRuntimeLogs,
    requestRuntimeDiagnostics,
    requestRuntimeStats,
    requestSettings,
    updateSetting,
    updateSettingValue,
    retry,
  };
}
