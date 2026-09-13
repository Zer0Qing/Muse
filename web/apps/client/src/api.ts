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
  readonly type: "hello" | "state.sync" | "session.select" | "session.new" | "session.rename" | "session.archive" | "session.delete" | "chat.send" | "chat.stop" | "chat.regenerate" | "chat.continue" | "tool.approval.resolve";
  readonly requestId?: string;
  readonly sessionId?: string;
  readonly text?: string;
  readonly generationId?: string;
  readonly title?: string;
  readonly archived?: boolean;
  readonly messageId?: string;
  readonly cursor?: number;
}

export interface HostMessage {
  readonly id: string;
  readonly role: "system" | "user" | "assistant" | "tool";
  readonly content: string;
  readonly reasoning?: string;
  readonly modelId?: string;
  readonly createdAt: number;
  readonly imageUrls?: readonly string[];
  readonly toolCallId?: string;
  readonly toolName?: string;
  readonly citationUrls?: readonly string[];
}

export interface HostEvent {
  readonly type: "state.snapshot" | "command.accepted" | "command.duplicate" | "error";
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
  readonly sessions?: readonly { id: string; title: string; assistantId: string; updatedAt: number; archived: boolean; pinned: boolean }[];
  readonly messages?: readonly HostMessage[];
  readonly pendingApprovals?: readonly { toolCallId: string; toolName: string; argumentsPreview: string }[];
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
  private readonly statusListeners = new Set<(status: HostConnectionStatus) => void>();
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
    for (const listener of this.statusListeners) listener(status);
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

  onStatus(listener: (status: HostConnectionStatus) => void): () => void {
    this.statusListeners.add(listener);
    listener(this.status);
    return () => this.statusListeners.delete(listener);
  }

  getCursor(): number { return this.lastCursor; }
  getStatus(): HostConnectionStatus { return this.status; }

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
