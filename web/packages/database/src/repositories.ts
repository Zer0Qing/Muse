import { createHash, randomUUID } from "node:crypto";
import type { ChatMessage } from "@muse/contracts";
import type { SessionRecord } from "@muse/core";
import type { DatabaseClient } from "./index.js";

export interface StoredAccount {
  readonly id: string;
  readonly email: string;
  readonly role: "user" | "platform_admin";
  readonly passwordHash: string;
  readonly createdAt: string;
}

export interface StoredSession {
  readonly id: string;
  readonly userId: string;
  readonly tokenHash: string;
  readonly expiresAt: string;
  readonly revokedAt: string | null;
}

function hashToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

/** Database-backed account repository. Password KDF remains in the API auth service. */
export class PostgresAccountRepository {
  constructor(private readonly db: DatabaseClient) {}

  async findByEmail(email: string): Promise<StoredAccount | undefined> {
    const rows = await this.db.query<{ id: string; email: string; role: "user" | "platform_admin"; password_hash: string; created_at: string }>(
      "select id, email, role, password_hash, created_at from user_accounts where email = $1 limit 1",
      [email.trim().toLowerCase()],
    );
    const row = rows[0];
    return row === undefined ? undefined : { id: row.id, email: row.email, role: row.role, passwordHash: row.password_hash, createdAt: row.created_at };
  }

  async findById(id: string): Promise<StoredAccount | undefined> {
    const rows = await this.db.query<{ id: string; email: string; role: "user" | "platform_admin"; password_hash: string; created_at: string }>(
      "select id, email, role, password_hash, created_at from user_accounts where id = $1 limit 1",
      [id],
    );
    const row = rows[0];
    return row === undefined ? undefined : { id: row.id, email: row.email, role: row.role, passwordHash: row.password_hash, createdAt: row.created_at };
  }

  async create(input: { readonly email: string; readonly passwordHash: string; readonly role?: StoredAccount["role"] }): Promise<StoredAccount> {
    const id = randomUUID();
    const rows = await this.db.query<{ id: string; email: string; role: "user" | "platform_admin"; password_hash: string; created_at: string }>(
      "insert into user_accounts(id, email, password_hash, role) values ($1, $2, $3, $4) returning id, email, role, password_hash, created_at",
      [id, input.email.trim().toLowerCase(), input.passwordHash, input.role ?? "user"],
    );
    const row = rows[0];
    if (row === undefined) throw new Error("account insert returned no row");
    return { id: row.id, email: row.email, role: row.role, passwordHash: row.password_hash, createdAt: row.created_at };
  }

  async list(): Promise<StoredAccount[]> {
    const rows = await this.db.query<{ id: string; email: string; role: "user" | "platform_admin"; password_hash: string; created_at: string }>(
      "select id, email, role, password_hash, created_at from user_accounts order by created_at desc",
    );
    return rows.map((row) => ({ id: row.id, email: row.email, role: row.role, passwordHash: row.password_hash, createdAt: row.created_at }));
  }
}

export class PostgresSessionRepository {
  constructor(private readonly db: DatabaseClient) {}

  async create(userId: string, token: string, expiresAt: Date): Promise<StoredSession> {
    const id = randomUUID();
    const rows = await this.db.query<{ id: string; user_id: string; token_hash: string; expires_at: string; revoked_at: string | null }>(
      "insert into auth_sessions(id, user_id, token_hash, expires_at) values ($1, $2, $3, $4) returning id, user_id, token_hash, expires_at, revoked_at",
      [id, userId, hashToken(token), expiresAt.toISOString()],
    );
    const row = rows[0];
    if (row === undefined) throw new Error("session insert returned no row");
    return { id: row.id, userId: row.user_id, tokenHash: row.token_hash, expiresAt: row.expires_at, revokedAt: row.revoked_at };
  }

  async findActive(token: string): Promise<StoredSession | undefined> {
    const rows = await this.db.query<{ id: string; user_id: string; token_hash: string; expires_at: string; revoked_at: string | null }>(
      "select id, user_id, token_hash, expires_at, revoked_at from auth_sessions where token_hash = $1 and revoked_at is null and expires_at > now() limit 1",
      [hashToken(token)],
    );
    const row = rows[0];
    return row === undefined ? undefined : { id: row.id, userId: row.user_id, tokenHash: row.token_hash, expiresAt: row.expires_at, revokedAt: row.revoked_at };
  }

  async revoke(token: string): Promise<void> {
    await this.db.query("update auth_sessions set revoked_at = now() where token_hash = $1 and revoked_at is null", [hashToken(token)]);
  }
}

export class PostgresChatRepository {
  constructor(private readonly db: DatabaseClient) {}

  async list(accountId: string): Promise<SessionRecord[]> {
    const rows = await this.db.query<{ id: string; user_id: string; title: string; assistant_id: string | null; archived: boolean; created_at: string; updated_at: string }>("select id, user_id, title, assistant_id, archived, created_at, updated_at from sessions where user_id = $1 order by updated_at desc", [accountId]);
    return rows.map((row) => ({ id: row.id, accountId: row.user_id, title: row.title, assistantId: row.assistant_id ?? "default", archived: row.archived, createdAt: new Date(row.created_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString() }));
  }

  async create(accountId: string, assistantId = "default"): Promise<SessionRecord> {
    const persistedAssistantId = assistantId === "default" ? null : assistantId;
    const rows = await this.db.query<{ id: string; user_id: string; title: string; assistant_id: string | null; archived: boolean; created_at: string; updated_at: string }>("insert into sessions(id, user_id, assistant_id) values ($1, $2, $3) returning id, user_id, title, assistant_id, archived, created_at, updated_at", [randomUUID(), accountId, persistedAssistantId]);
    const row = rows[0];
    if (!row) throw new Error("session insert returned no row");
    return { id: row.id, accountId: row.user_id, title: row.title, assistantId: row.assistant_id ?? "default", archived: row.archived, createdAt: new Date(row.created_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString() };
  }

  async get(accountId: string, sessionId: string): Promise<SessionRecord | undefined> {
    const rows = await this.db.query<{ id: string; user_id: string; title: string; assistant_id: string | null; archived: boolean; created_at: string; updated_at: string }>("select id, user_id, title, assistant_id, archived, created_at, updated_at from sessions where id = $1 and user_id = $2 limit 1", [sessionId, accountId]);
    const row = rows[0];
    return row === undefined ? undefined : { id: row.id, accountId: row.user_id, title: row.title, assistantId: row.assistant_id ?? "default", archived: row.archived, createdAt: new Date(row.created_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString() };
  }

  async appendMessage(userId: string, sessionId: string, message: ChatMessage, clientMessageId?: string): Promise<void> {
    await this.db.transaction(async (transaction) => {
      const session = await transaction.query("select id from sessions where id = $1 and user_id = $2 for update", [sessionId, userId]);
      if (session.length === 0) throw new Error("session not found");
      await transaction.query(
        "insert into messages(id, session_id, role, content, reasoning, client_message_id) values ($1, $2, $3, $4, $5, $6) on conflict (session_id, client_message_id) do nothing",
        [message.id, sessionId, message.role, message.content, message.reasoning ?? null, clientMessageId ?? message.id],
      );
      await transaction.query("update sessions set updated_at = now() where id = $1", [sessionId]);
    });
  }

  async listMessages(userId: string, sessionId: string): Promise<ChatMessage[]> {
    const session = await this.get(userId, sessionId);
    if (session === undefined) throw new Error("session not found");
    const rows = await this.db.query<{ id: string; role: ChatMessage["role"]; content: string; reasoning: string | null; created_at: string }>(
      "select id, role, content, reasoning, created_at from messages where session_id = $1 order by created_at, id",
      [sessionId],
    );
    return rows.map((row) => ({ id: row.id, role: row.role, content: row.content, ...(row.reasoning === null ? {} : { reasoning: row.reasoning }), createdAt: row.created_at }));
  }
}
