import type { ChatMessage, ProviderConfig } from "@muse/contracts";
import type { AssistantRecord } from "./assistant.js";
export type { AssistantRecord } from "./assistant.js";

export interface SessionRecord {
  readonly id: string;
  readonly accountId: string;
  readonly title: string;
  readonly assistantId: string;
  readonly archived: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SessionRepository {
  list(accountId: string): Promise<SessionRecord[]>;
  create(accountId: string, assistantId?: string): Promise<SessionRecord>;
  get(accountId: string, sessionId: string): Promise<SessionRecord | undefined>;
  listMessages(accountId: string, sessionId: string): Promise<ChatMessage[]>;
  appendMessage(accountId: string, sessionId: string, message: ChatMessage): Promise<void>;
}

export interface ProviderSecretStore {
  /** Return a key only for the duration of a request; implementations must decrypt lazily. */
  resolveRequestKey(accountId: string, providerId: string): Promise<string | undefined>;
}

/** Development repository; replace its maps with PostgreSQL repositories without changing API handlers. */
export class InMemorySessionRepository implements SessionRepository {
  private readonly sessions = new Map<string, SessionRecord>();
  private readonly messages = new Map<string, ChatMessage[]>();

  async list(accountId: string): Promise<SessionRecord[]> {
    return [...this.sessions.values()].filter((session) => session.accountId === accountId).map((session) => ({ ...session }));
  }

  async create(accountId: string, assistantId = "default"): Promise<SessionRecord> {
    const now = new Date().toISOString();
    const session: SessionRecord = { id: crypto.randomUUID(), accountId, assistantId, title: "新对话", archived: false, createdAt: now, updatedAt: now };
    this.sessions.set(session.id, session);
    this.messages.set(session.id, []);
    return { ...session };
  }

  async get(accountId: string, sessionId: string): Promise<SessionRecord | undefined> {
    const session = this.sessions.get(sessionId);
    return session?.accountId === accountId ? { ...session } : undefined;
  }

  async listMessages(accountId: string, sessionId: string): Promise<ChatMessage[]> {
    if (!await this.get(accountId, sessionId)) throw new Error("session not found");
    return [...(this.messages.get(sessionId) ?? [])].map((message) => ({ ...message }));
  }

  async appendMessage(accountId: string, sessionId: string, message: ChatMessage): Promise<void> {
    const session = await this.get(accountId, sessionId);
    if (!session) throw new Error("session not found");
    this.messages.set(sessionId, [...(this.messages.get(sessionId) ?? []), { ...message }]);
    this.sessions.set(sessionId, { ...session, updatedAt: new Date().toISOString() });
  }
}
