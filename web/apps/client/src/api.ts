import type { ChatMessage, ChatStreamEvent, ProviderConfig } from "@muse/contracts";
import { FetchProviderTransport } from "@muse/provider";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export interface SessionRecord {
  readonly id: string;
  readonly title: string;
  readonly assistantId: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ClientSession {
  readonly token: string;
  readonly userId: string;
  readonly expiresAt: number;
}

export interface StoredProviderConfig extends ProviderConfig {
  readonly accountId: string;
  readonly hasSecret: boolean;
}

export interface AssistantRecord {
  readonly id: string;
  readonly accountId: string;
  readonly name: string;
  readonly identityPrompt: string;
  readonly relationshipPrompt: string;
  readonly stylePrompt: string;
  readonly providerId?: string;
  readonly modelId?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface MemoryFact {
  readonly id: string;
  readonly content: string;
  readonly importance: 0 | 1 | 2;
  readonly confidence: number;
  readonly hitCount: number;
  readonly pinnedAt?: string;
  readonly updatedAt: string;
}

export interface RagChunk {
  readonly id: string;
  readonly documentId: string;
  readonly ordinal: number;
  readonly text: string;
  readonly tokenEstimate: number;
}

export async function apiJson<T>(path: string, init: RequestInit = {}, session?: ClientSession): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json");
  if (session) headers.set("authorization", `Bearer ${session.token}`);
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  const body: unknown = await response.json();
  if (!response.ok) {
    const message = body && typeof body === "object" && "message" in body && typeof body.message === "string" ? body.message : `request failed (${response.status})`;
    throw new Error(message);
  }
  return body as T;
}

export async function login(email: string, password: string): Promise<ClientSession> {
  const result = await apiJson<{ session: ClientSession }>("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) });
  return result.session;
}

export async function register(email: string, password: string): Promise<void> {
  await apiJson("/api/v1/auth/register", { method: "POST", body: JSON.stringify({ email, password }) });
}

export async function listSessions(session: ClientSession): Promise<SessionRecord[]> {
  const result = await apiJson<{ sessions: SessionRecord[] }>("/api/v1/sessions", {}, session);
  return result.sessions;
}

export async function createSession(session: ClientSession, assistantId?: string): Promise<SessionRecord> {
  const result = await apiJson<{ session: SessionRecord }>("/api/v1/sessions", { method: "POST", body: JSON.stringify(assistantId ? { assistantId } : {}) }, session);
  return result.session;
}

export async function listProviders(session: ClientSession): Promise<StoredProviderConfig[]> {
  const result = await apiJson<{ providers: StoredProviderConfig[] }>("/api/v1/providers", {}, session);
  return result.providers;
}

export async function saveProvider(session: ClientSession, provider: ProviderConfig, apiKey: string): Promise<StoredProviderConfig> {
  const result = await apiJson<{ provider: StoredProviderConfig }>("/api/v1/providers", { method: "POST", headers: { "x-muse-provider-key": apiKey }, body: JSON.stringify(provider) }, session);
  return result.provider;
}

export async function listAssistants(session: ClientSession): Promise<AssistantRecord[]> {
  const result = await apiJson<{ assistants: AssistantRecord[] }>("/api/v1/assistants", {}, session);
  return result.assistants;
}

export async function createAssistant(session: ClientSession, input: Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">): Promise<AssistantRecord> {
  const result = await apiJson<{ assistant: AssistantRecord }>("/api/v1/assistants", { method: "POST", body: JSON.stringify(input) }, session);
  return result.assistant;
}

export async function updateAssistant(session: ClientSession, assistant: AssistantRecord): Promise<AssistantRecord> {
  const result = await apiJson<{ assistant: AssistantRecord }>(`/api/v1/assistants/${encodeURIComponent(assistant.id)}`, { method: "PATCH", body: JSON.stringify({ name: assistant.name, identityPrompt: assistant.identityPrompt, relationshipPrompt: assistant.relationshipPrompt, stylePrompt: assistant.stylePrompt }) }, session);
  return result.assistant;
}

export async function searchMemory(session: ClientSession, query: string, assistantId = "default", spaceId = "default"): Promise<MemoryFact[]> {
  const params = new URLSearchParams({ q: query, assistantId, spaceId });
  const result = await apiJson<{ facts: MemoryFact[] }>(`/api/v1/memory/search?${params}`, {}, session);
  return result.facts;
}

export async function createMemorySpace(session: ClientSession, name: string, scope = "main"): Promise<{ id: string; name: string; scope: string }> {
  const result = await apiJson<{ space: { id: string; name: string; scope: string } }>("/api/v1/memory/spaces", { method: "POST", body: JSON.stringify({ name, scope }) }, session);
  return result.space;
}

export async function addMemoryFact(session: ClientSession, input: { spaceId: string; content: string; assistantId?: string; importance?: 0 | 1 | 2 }): Promise<MemoryFact> {
  const result = await apiJson<{ fact: MemoryFact }>("/api/v1/memory/facts", { method: "POST", body: JSON.stringify(input) }, session);
  return result.fact;
}

export async function searchRag(session: ClientSession, query: string): Promise<{ chunks: RagChunk[]; citations: Array<{ citationId: string; documentId: string; title: string; excerpt: string }> }> {
  const result = await apiJson<{ chunks: RagChunk[]; citations: Array<{ citationId: string; documentId: string; title: string; excerpt: string }> }>(`/api/v1/rag/search?q=${encodeURIComponent(query)}`, {}, session);
  return result;
}

export async function indexRagDocument(session: ClientSession, documentId: string, text: string): Promise<RagChunk[]> {
  const result = await apiJson<{ chunks: RagChunk[] }>("/api/v1/rag/documents", { method: "POST", body: JSON.stringify({ documentId, text }) }, session);
  return result.chunks;
}

export interface FileUploadPlan {
  readonly objectKey: string;
  readonly downloadName: string;
  readonly upload: { readonly url: string; readonly expiresAt: string };
}

export async function presignFile(session: ClientSession, input: { fileId: string; name: string; mimeType: string; sizeBytes: number }): Promise<FileUploadPlan> {
  return apiJson<FileUploadPlan>("/api/v1/files/presign", { method: "POST", body: JSON.stringify(input) }, session);
}

export async function completeFile(session: ClientSession, input: { fileId: string; name: string; mimeType: string; sizeBytes: number; objectKey: string; sha256: string }): Promise<void> {
  await apiJson("/api/v1/files/complete", { method: "POST", body: JSON.stringify(input) }, session);
}

export async function registerPushSubscription(session: ClientSession, subscription: PushSubscription): Promise<void> {
  const jsonSubscription = subscription.toJSON();
  if (!jsonSubscription.endpoint || !jsonSubscription.keys?.p256dh || !jsonSubscription.keys.auth) throw new Error("browser push subscription is incomplete");
  await apiJson("/api/v1/push/subscriptions", { method: "POST", body: JSON.stringify({ endpoint: jsonSubscription.endpoint, p256dh: jsonSubscription.keys.p256dh, auth: jsonSubscription.keys.auth, userAgent: navigator.userAgent }) }, session);
}

export async function listMessages(session: ClientSession, sessionId: string): Promise<ChatMessage[]> {
  const result = await apiJson<{ messages: ChatMessage[] }>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages`, {}, session);
  return result.messages;
}

/** Persist a message produced by the browser-side BYOK transport. */
export async function appendMessage(session: ClientSession, sessionId: string, message: ChatMessage): Promise<void> {
  await apiJson(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages`, { method: "POST", body: JSON.stringify(message) }, session);
}

export interface HostCommand {
  readonly protocolVersion?: number;
  readonly type: "hello" | "state.sync" | "session.select" | "session.new" | "session.rename" | "session.archive" | "session.delete" | "chat.send" | "chat.stop" | "chat.retry" | "chat.regenerate" | "chat.continue" | "tool.approval.resolve" | "history.page" | "history.search" | "message.copy" | "message.quote" | "message.delete" | "message.favorite" | "settings.snapshot" | "settings.update" | "assistants.list" | "assistants.detail" | "memory.list" | "memory.search" | "knowledge.list" | "knowledge.documents" | "tasks.list" | "system.status" | "providers.list" | "sessions.detail";
  readonly requestId?: string;
  readonly sessionId?: string;
  /** session.new 的目标助手 id；不传则 Host 用默认助手。 */
  readonly assistantId?: string;
  /** knowledge.documents 的目标知识库。 */
  readonly kbId?: string;
  /** chat.send 的引用：被引用消息的 id（Host 只在同一会话内接受）。 */
  readonly quotedMessageId?: string;
  readonly text?: string;
  readonly generationId?: string;
  readonly title?: string;
  readonly archived?: boolean;
  readonly messageId?: string;
  /** `cursor`：state.sync 的事件游标（数字）；memory.list 的分页游标是上一页最后一条 id（字符串）。 */
  /** message.delete 的批量目标（Host 逐条删并在回执里报 deletedCount）。 */
  readonly messageIds?: readonly string[];
  /** message.favorite 的目标状态：true = 收藏，false = 取消收藏。 */
  readonly favored?: boolean;
  /** state.sync 的事件游标是数字；memory.list 的分页游标是上一页最后一条 id（字符串）。 */
  readonly cursor?: string | number;
  /** history.page 分页：offset 跳过最近多少条，limit 每页条数。 */
  readonly offset?: number;
  readonly limit?: number;
  /** history.search 关键词。 */
  readonly query?: string;
  /** settings.update 目标键，必须命中服务端白名单。 */
  readonly key?: string;
  /**
   * settings.update 目标值，按该键声明的类型校验。
   * 布尔仍以 `"true"` / `"false"` 字符串下发（与 Host 现有解析一致），数字与枚举直接给原类型。
   */
  readonly value?: string | number | boolean;
}

export interface HostSettingsEntry {
  readonly id: string;
  /** 标题的字符串资源名：稳定标识（导航/断言用），不直接显示。 */
  readonly titleKey: string;
  readonly descKey?: string;
  /** Host 按当前应用语言解析好的标题。 */
  readonly title: string;
  /** Host 解析好的说明；没有说明的条目为空。 */
  readonly desc?: string;
  readonly groupKey: string;
  readonly iconKey: string;
  readonly kind: "link" | "switch" | "action";
  readonly enabled?: boolean;
  readonly toggleKey?: string;
  readonly checked?: boolean;
}

export interface HostSettingsGroup {
  /** 分组标题的字符串资源名。 */
  readonly key: string;
  /** Host 按当前应用语言解析好的分组标题。 */
  readonly groupTitle: string;
  readonly entries: readonly HostSettingsEntry[];
}

export type HostSettingValueType = "boolean" | "number" | "enum" | "text";

/** 枚举项：`label` 由 Host 按当前语言解析后下发，Web 端不自建文案表。 */
export interface HostSettingOption {
  readonly value: string;
  readonly label: string;
}

/**
 * settings.snapshot 里的一个可调项。
 *
 * `type` 决定控件（开关 / 加减 / 可展开单选 / 只读行），`value` 的类型跟 `type` 对齐：
 * boolean → boolean，number → number，enum → string（命中 `options[].value`），text → string。
 */
export interface HostSettingValue {
  readonly key: string;
  readonly type: HostSettingValueType;
  readonly value: unknown;
  /** 仅 type=enum：可选项，顺序即展示顺序。 */
  readonly options?: readonly HostSettingOption[];
}

export interface HostSettingsSnapshot {
  readonly groups: readonly HostSettingsGroup[];
  readonly toggles: readonly { readonly key: string; readonly enabled: boolean }[];
  readonly display: { readonly language: string; readonly themeMode: string; readonly fontSizeScale: string; readonly themeId: string };
  readonly writableKeys: readonly string[];
  /** 子页的可调项；老 Host 不下发（undefined 不等于空数组）。 */
  readonly values?: readonly HostSettingValue[];
}

export interface HostAssistantSummary {
  readonly id: string;
  readonly name: string;
  readonly avatarUri?: string;
  readonly isDefault?: boolean;
}

/** assistants.detail 的载荷：只读参数 + 人设摘要 + 统计。 */
export interface HostAssistantDetail {
  readonly id: string;
  readonly name: string;
  readonly avatarUri?: string | undefined;
  readonly description?: string | undefined;
  readonly modelId?: string | undefined;
  readonly providerId?: string | undefined;
  readonly temperature?: number | undefined;
  readonly topP?: number | undefined;
  readonly maxTokens?: number | undefined;
  readonly personaSummary?: string | undefined;
  readonly systemPromptLength?: number | undefined;
  readonly toolCount?: number | undefined;
  readonly contextMessageSize?: number | undefined;
  readonly reasoningLevel?: string | undefined;
  readonly enabled?: boolean | undefined;
}

/** system.status 里的存储占用（字节）。 */
export interface HostSystemStorage {
  readonly databaseBytes: number;
  readonly mediaBytes: number;
  readonly voiceBytes: number;
  readonly workspaceBytes: number;
  readonly cacheBytes: number;
  readonly totalAppBytes: number;
}

export interface HostSystemBackup {
  readonly lastBackupAt?: number | undefined;
  readonly lastBackupBytes?: number | undefined;
  readonly autoBackupEnabled: boolean;
  readonly cloudBackupEnabled: boolean;
}

/** 平台专有能力：true=可用、false=未开启、undefined=不可用。 */
export interface HostSystemCapabilities {
  readonly accessibility?: boolean | undefined;
  readonly shell?: boolean | undefined;
  readonly root?: boolean | undefined;
  readonly shizuku?: boolean | undefined;
  readonly virtualDisplay?: boolean | undefined;
  readonly notificationListener?: boolean | undefined;
}

/** system.status 的载荷。 */
export interface HostSystemStatus {
  readonly appVersion: string;
  readonly appVersionCode: number;
  readonly uptimeMs: number;
  readonly storage: HostSystemStorage;
  readonly backup: HostSystemBackup;
  readonly capabilities: HostSystemCapabilities;
  readonly battery: { readonly level?: number | undefined; readonly charging?: boolean | undefined };
}

/** 一个知识库。 */
export interface HostKnowledgeBase {
  readonly id: string;
  readonly name: string;
  readonly documentCount: number;
  readonly chunkCount: number;
  readonly createdAt: number;
  readonly updatedAt: number;
  /** 当前用的 embedding 后端。 */
  readonly embeddingBackend?: string | undefined;
}

/** 知识库里的一个文档。 */
export interface HostKnowledgeDocument {
  readonly id: string;
  readonly title: string;
  readonly chunkCount: number;
  readonly sizeBytes: number;
  readonly createdAt: number;
  /** Host 给的索引状态（原样展示）。 */
  readonly status?: string | undefined;
}

/** 一个定时任务。 */
export interface HostScheduledTask {
  readonly id: string;
  readonly name: string;
  readonly cron: string;
  readonly enabled: boolean;
  readonly nextRunAt?: number | undefined;
  readonly lastRunAt?: number | undefined;
  readonly lastResult?: string | undefined;
}

/** 一条记忆。 */
export interface HostMemoryItem {
  readonly id: string;
  readonly content: string;
  readonly createdAt: number;
  readonly source?: string | undefined;
  readonly pinned?: boolean | undefined;
  /** memory.search 命中时的片段。 */
  readonly snippet?: string | undefined;
}

/** memory.list / memory.search 的分页结果。 */
export interface HostMemoryPage {
  readonly items: readonly HostMemoryItem[];
  readonly hasMore: boolean;
}

export interface HostProviderSummary {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly models: readonly { readonly id: string; readonly name: string }[];
}

export interface HostSessionDetail {
  readonly id: string;
  readonly title: string;
  readonly assistantId: string;
  readonly archived: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly messageCount: number;
}

export interface HostToolCall {
  readonly callId: string;
  readonly name: string;
  readonly argumentsPreview?: string | undefined;
  readonly resultPreview?: string | undefined;
  readonly success?: boolean | undefined;
  readonly durationMs?: number | undefined;
}

export type HostMessageStatus = "sending" | "streaming" | "sent" | "failed";

export interface HostMessage {
  readonly id: string;
  readonly role: "system" | "user" | "assistant" | "tool";
  readonly content: string;
  readonly reasoning?: string;
  readonly modelId?: string;
  readonly createdAt: number;
  /**
   * 可直接访问的地址：新 Host 给同源相对路径 `/api/media/<token>`（Cookie 自动带），
   * 旧数据可能仍是 `data:` URI / http URL / 裸 base64 / `file://` 绝对路径。
   */
  readonly imageUrls?: readonly string[];
  readonly toolCallId?: string;
  readonly toolName?: string;
  readonly citationUrls?: readonly string[];
  /** 助手发出的语音消息地址，同样是可访问路径。 */
  readonly voiceUrl?: string | undefined;
  /** 语音时长，毫秒。 */
  readonly voiceDurationMs?: number | undefined;
  /** 这条消息上的工具调用明细（可能多个）；老 Host 不下发。 */
  readonly toolCalls?: readonly HostToolCall[] | undefined;
  /** 消息级发送状态；老 Host 不下发。 */
  readonly status?: HostMessageStatus | undefined;
  /** 仅 status=failed 时有值。 */
  readonly errorText?: string | undefined;
  /** 是否已收藏；老 Host 不下发时按未收藏处理。 */
  readonly favorite?: boolean | undefined;
  /** 引用回复的被引用正文；Host 不下发时不显示引用块。 */
  readonly quotedContent?: string | undefined;
}

export interface HostEvent {
  readonly type: "state.snapshot" | "command.accepted" | "command.duplicate" | "error" | "history.page" | "history.search" | "message.copy" | "message.quote" | "settings.snapshot" | "assistants.list" | "assistants.detail" | "memory.list" | "memory.search" | "knowledge.list" | "knowledge.documents" | "tasks.list" | "system.status" | "providers.list" | "sessions.detail";
  readonly protocolVersion?: number;
  readonly requestId?: string;
  readonly connectionId?: string;
  readonly eventSeq?: number;
  readonly cursor?: number;
  readonly generationId?: string;
  readonly turnId?: string;
  readonly sessionId?: string;
  readonly isStreaming?: boolean;
  readonly error?: string;
  /** command.accepted 里回带的目标消息（如 chat.send / chat.retry）。 */
  readonly messageId?: string;
  /** message.delete 的回执：实际删掉了几条。 */
  readonly deletedCount?: number;
  /** message.delete 的回执：被跳过的 id（例如正在生成、不让删的那条）。 */
  readonly skippedMessageIds?: readonly string[];
  /** message.copy / message.quote 回带的正文。 */
  readonly content?: string;
  /** Host 声明的能力位：media / tool_call_details / message_status 等。老 Host 为空。 */
  readonly capabilityFlags?: readonly string[];
  readonly sessions?: readonly { id: string; title: string; assistantId: string; updatedAt: number; archived: boolean; pinned: boolean }[];
  readonly messages?: readonly HostMessage[];
  readonly pendingApprovals?: readonly { toolCallId: string; toolName: string; argumentsPreview: string }[];
  readonly settings?: HostSettingsSnapshot;
  readonly assistants?: readonly HostAssistantSummary[];
  readonly providers?: readonly HostProviderSummary[];
  readonly selectedModelId?: string;
  readonly sessionDetail?: HostSessionDetail;
  /** assistants.detail 的载荷；不存在时为空并带 error=assistant_not_found。 */
  readonly assistantDetail?: HostAssistantDetail;
  /** memory.list / memory.search 的载荷（默认 30 条、上限 100）。 */
  readonly items?: readonly HostMemoryItem[];
  readonly hasMore?: boolean;
  /** knowledge.list 的载荷。 */
  readonly bases?: readonly HostKnowledgeBase[];
  /** knowledge.documents 的载荷（分页与 items 共用一个 hasMore）。 */
  readonly documents?: readonly HostKnowledgeDocument[];
  /** tasks.list 的载荷。 */
  readonly tasks?: readonly HostScheduledTask[];
  /** system.status 的载荷。 */
  readonly system?: HostSystemStatus;
}

function hostBase(): string {
  return (import.meta.env.VITE_HOST_BASE ?? window.location.origin).replace(/\/$/, "");
}

export async function hostPinLogin(pin: string): Promise<void> {
  const response = await fetch(`${hostBase()}/api/auth/pin-login`, {
    method: "POST",
    credentials: "include",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ pin }),
  });
  const body: unknown = await response.json();
  if (!response.ok) {
    const message = body && typeof body === "object" && "message" in body && typeof body.message === "string" ? body.message : "Host PIN 登录失败";
    throw new Error(message);
  }
}

export async function hostLogout(): Promise<void> {
  await fetch(`${hostBase()}/api/auth/logout`, { method: "POST", credentials: "include" });
}

export type HostConnectionStatus = "connected" | "reconnecting" | "closed";

export class HostConnection {
  private readonly listeners = new Set<(event: HostEvent) => void>();
  private readonly closeListeners = new Set<() => void>();
  private readonly statusListeners = new Set<(status: HostConnectionStatus, attempt: number) => void>();
  private socket: WebSocket;
  private lastCursor = 0;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  private closed = false;
  private status: HostConnectionStatus = "connected";

  constructor(socket: WebSocket, private readonly url: string) {
    this.socket = socket;
    this.attachSocket(socket, false);
  }

  private attachSocket(socket: WebSocket, resyncOnOpen: boolean): void {
    this.socket = socket;
    socket.addEventListener("message", (event) => {
      if (typeof event.data !== "string") return;
      let parsed: unknown;
      try { parsed = JSON.parse(event.data); } catch { return; }
      if (parsed && typeof parsed === "object" && "type" in parsed) {
        const hostEvent = parsed as HostEvent;
        if (typeof hostEvent.cursor === "number") this.lastCursor = hostEvent.cursor;
        for (const listener of this.listeners) listener(hostEvent);
      }
    });
    socket.addEventListener("open", () => {
      if (this.closed || socket !== this.socket) return;
      this.reconnectAttempt = 0;
      this.notifyStatus("connected");
      if (resyncOnOpen) {
        this.send({ type: "state.sync", requestId: crypto.randomUUID() });
      }
    }, { once: true });
    const recover = () => {
      if (this.closed || socket !== this.socket) return;
      this.notifyStatus("reconnecting");
      this.scheduleReconnect();
    };
    socket.addEventListener("error", () => {
      if (this.closed || socket !== this.socket) return;
      socket.close();
      recover();
    }, { once: true });
    socket.addEventListener("close", () => {
      if (this.closed || socket !== this.socket) return;
      for (const listener of this.closeListeners) listener();
      recover();
    }, { once: true });
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer !== undefined) return;
    const delay = Math.min(15_000, 1_000 * (2 ** Math.min(this.reconnectAttempt, 4)));
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      if (this.closed) return;
      try {
        this.attachSocket(new WebSocket(this.url), true);
      } catch {
        this.scheduleReconnect();
      }
    }, delay);
  }

  private notifyStatus(status: HostConnectionStatus): void {
    this.status = status;
    // 同时给出「第几次重连」：界面要据此区分「正在重连（第 N 次）」与「放弃重连」。
    for (const listener of this.statusListeners) listener(status, this.reconnectAttempt);
  }

  send(command: HostCommand): void {
    if (this.closed || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("Muse Host 正在重新连接");
    }
    const enriched = command.type === "state.sync" && command.cursor === undefined
      ? { ...command, protocolVersion: 1, cursor: this.lastCursor }
      : { ...command, protocolVersion: 1 };
    this.socket.send(JSON.stringify(enriched));
  }

  subscribe(listener: (event: HostEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onClose(listener: () => void): () => void {
    this.closeListeners.add(listener);
    return () => this.closeListeners.delete(listener);
  }

  onStatus(listener: (status: HostConnectionStatus, attempt: number) => void): () => void {
    this.statusListeners.add(listener);
    listener(this.status, this.reconnectAttempt);
    return () => this.statusListeners.delete(listener);
  }

  getCursor(): number { return this.lastCursor; }
  getStatus(): HostConnectionStatus { return this.status; }
  /** 已发起的重连次数（连接成功后归零）。 */
  getReconnectAttempt(): number { return this.reconnectAttempt; }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.reconnectTimer !== undefined) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.notifyStatus("closed");
    this.socket.close();
  }
}

export async function connectHost(): Promise<HostConnection> {
  const url = new URL(hostBase());
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/ws";
  const target = url.toString();
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      const socket = await new Promise<WebSocket>((resolve, reject) => {
        const candidate = new WebSocket(target);
        let settled = false;
        const fail = () => {
          if (!settled) {
            settled = true;
            candidate.close();
            reject(new Error("无法连接 Muse Host WebSocket"));
          }
        };
        candidate.addEventListener("open", () => {
          if (!settled) {
            settled = true;
            resolve(candidate);
          }
        }, { once: true });
        candidate.addEventListener("error", fail, { once: true });
        candidate.addEventListener("close", fail, { once: true });
      });
      return new HostConnection(socket, target);
    } catch (error) {
      if (attempt === 4) throw error;
      await new Promise((resolve) => setTimeout(resolve, 500 * (attempt + 1)));
    }
  }
  throw new Error("无法连接 Muse Host WebSocket");
}

export interface StreamChatInput {
  readonly session: ClientSession;
  readonly sessionId: string;
  readonly messages: readonly ChatMessage[];
  readonly provider: ProviderConfig;
  readonly providerId?: string;
  readonly model: string;
  readonly apiKey: string;
  readonly mode?: "byok" | "hosted";
  readonly transport?: "direct" | "proxy";
  readonly signal?: AbortSignal;
}

export async function* streamDirectByok(input: Omit<StreamChatInput, "session" | "sessionId">): AsyncGenerator<ChatStreamEvent> {
  const transport = new FetchProviderTransport();
  yield* transport.stream({ messages: input.messages, provider: input.provider, model: input.model, mode: "byok" }, input.apiKey, input.signal ?? new AbortController().signal);
}

export async function* streamChat(input: StreamChatInput): AsyncGenerator<ChatStreamEvent> {
  const headers = new Headers({ "content-type": "application/json", authorization: `Bearer ${input.session.token}` });
  if ((input.mode ?? "byok") === "byok") headers.set("x-muse-provider-key", input.apiKey);
  const response = await fetch(`${API_BASE}/api/v1/chat/stream`, {
    method: "POST",
    headers,
    body: JSON.stringify({ sessionId: input.sessionId, messages: input.messages, provider: input.provider, providerId: input.providerId, model: input.model, mode: input.mode ?? "byok" }),
    ...(input.signal === undefined ? {} : { signal: input.signal }),
  });
  if (!response.ok || !response.body) throw new Error(`chat request failed (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      buffer += decoder.decode(part.value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) {
        const data = frame.split("\n").find((line) => line.startsWith("data:"))?.slice(5).trim();
        if (!data) continue;
        yield JSON.parse(data) as ChatStreamEvent;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
