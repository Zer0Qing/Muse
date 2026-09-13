import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import type { DatabaseClient } from "@muse/database";

export type UserRole = "user" | "platform_admin";

export interface UserAccount {
  readonly id: string;
  readonly email: string;
  readonly role: UserRole;
  readonly passwordDigest: string;
  readonly createdAt: string;
}

export interface AuthSession {
  readonly token: string;
  readonly userId: string;
  readonly expiresAt: number;
}

export interface AuthStore {
  findByEmail(email: string): Promise<UserAccount | undefined>;
  findById(id: string): Promise<UserAccount | undefined>;
  createUser(input: { readonly email: string; readonly passwordDigest: string; readonly role: UserRole }): Promise<UserAccount>;
  createSession(input: AuthSession): Promise<void>;
  findSession(token: string): Promise<AuthSession | undefined>;
  revokeSession(token: string): Promise<void>;
  listUsers(): Promise<UserAccount[]>;
}

const PASSWORD_KEY_LENGTH = 64;

function passwordDigest(password: string): string {
  const salt = randomBytes(16);
  const derived = scryptSync(password, salt, PASSWORD_KEY_LENGTH, { N: 16_384, r: 8, p: 1 });
  return `scrypt$${salt.toString("base64url")}$${derived.toString("base64url")}`;
}

function verifyPassword(password: string, stored: string): boolean {
  const [algorithm, saltText, expectedText] = stored.split("$");
  if (algorithm !== "scrypt" || !saltText || !expectedText) return false;
  try {
    const salt = Buffer.from(saltText, "base64url");
    const expected = Buffer.from(expectedText, "base64url");
    const actual = scryptSync(password, salt, PASSWORD_KEY_LENGTH, { N: 16_384, r: 8, p: 1 });
    return expected.length === actual.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

function publicUser(user: UserAccount): UserAccount {
  return { ...user, passwordDigest: "[redacted]" };
}

class InMemoryAuthStore implements AuthStore {
  private readonly users = new Map<string, UserAccount>();
  private readonly sessions = new Map<string, AuthSession>();

  async findByEmail(email: string): Promise<UserAccount | undefined> { return [...this.users.values()].find((user) => user.email === email); }
  async findById(id: string): Promise<UserAccount | undefined> { return this.users.get(id); }
  async createUser(input: { readonly email: string; readonly passwordDigest: string; readonly role: UserRole }): Promise<UserAccount> {
    const user: UserAccount = { id: randomUUID(), email: input.email, role: input.role, passwordDigest: input.passwordDigest, createdAt: new Date().toISOString() };
    this.users.set(user.id, user);
    return user;
  }
  async createSession(input: AuthSession): Promise<void> { this.sessions.set(input.token, { ...input }); }
  async findSession(token: string): Promise<AuthSession | undefined> { return this.sessions.get(token); }
  async revokeSession(token: string): Promise<void> { this.sessions.delete(token); }
  async listUsers(): Promise<UserAccount[]> { return [...this.users.values()]; }
}

/** PostgreSQL auth store. It persists only password digests and token hashes, never raw bearer tokens. */
export class PostgresAuthStore implements AuthStore {
  constructor(private readonly db: DatabaseClient) {}

  async findByEmail(email: string): Promise<UserAccount | undefined> {
    const rows = await this.db.query<{ id: string; email: string; role: UserRole; password_hash: string; created_at: string }>(
      "select id, email, role, password_hash, created_at from user_accounts where email = $1 limit 1",
      [email],
    );
    const row = rows[0];
    return row === undefined ? undefined : { id: row.id, email: row.email, role: row.role, passwordDigest: row.password_hash, createdAt: row.created_at };
  }

  async findById(id: string): Promise<UserAccount | undefined> {
    const rows = await this.db.query<{ id: string; email: string; role: UserRole; password_hash: string; created_at: string }>(
      "select id, email, role, password_hash, created_at from user_accounts where id = $1 limit 1",
      [id],
    );
    const row = rows[0];
    return row === undefined ? undefined : { id: row.id, email: row.email, role: row.role, passwordDigest: row.password_hash, createdAt: row.created_at };
  }

  async createUser(input: { readonly email: string; readonly passwordDigest: string; readonly role: UserRole }): Promise<UserAccount> {
    const rows = await this.db.query<{ id: string; email: string; role: UserRole; password_hash: string; created_at: string }>(
      "insert into user_accounts(id, email, password_hash, role) values ($1, $2, $3, $4) returning id, email, role, password_hash, created_at",
      [randomUUID(), input.email, input.passwordDigest, input.role],
    );
    const row = rows[0];
    if (row === undefined) throw new Error("account insert returned no row");
    return { id: row.id, email: row.email, role: row.role, passwordDigest: row.password_hash, createdAt: row.created_at };
  }

  async createSession(input: AuthSession): Promise<void> {
    const tokenHash = (await import("node:crypto")).createHash("sha256").update(input.token, "utf8").digest("hex");
    await this.db.query(
      "insert into auth_sessions(id, user_id, token_hash, expires_at) values ($1, $2, $3, $4)",
      [randomUUID(), input.userId, tokenHash, new Date(input.expiresAt).toISOString()],
    );
  }

  async findSession(token: string): Promise<AuthSession | undefined> {
    const tokenHash = (await import("node:crypto")).createHash("sha256").update(token, "utf8").digest("hex");
    const rows = await this.db.query<{ user_id: string; expires_at: string }>(
      "select user_id, expires_at from auth_sessions where token_hash = $1 and revoked_at is null and expires_at > now() limit 1",
      [tokenHash],
    );
    const row = rows[0];
    return row === undefined ? undefined : { token, userId: row.user_id, expiresAt: new Date(row.expires_at).getTime() };
  }

  async revokeSession(token: string): Promise<void> {
    const tokenHash = (await import("node:crypto")).createHash("sha256").update(token, "utf8").digest("hex");
    await this.db.query("update auth_sessions set revoked_at = now() where token_hash = $1 and revoked_at is null", [tokenHash]);
  }

  async listUsers(): Promise<UserAccount[]> {
    const rows = await this.db.query<{ id: string; email: string; role: UserRole; password_hash: string; created_at: string }>(
      "select id, email, role, password_hash, created_at from user_accounts order by created_at desc",
    );
    return rows.map((row) => ({ id: row.id, email: row.email, role: row.role, passwordDigest: row.password_hash, createdAt: row.created_at }));
  }
}

/** Async auth service with persistent-store boundary and memory fallback for local development. */
export class AuthService {
  constructor(private readonly store: AuthStore = new InMemoryAuthStore()) {}

  async register(email: string, password: string): Promise<UserAccount> {
    const normalized = email.trim().toLowerCase();
    if (!/^\S+@\S+\.\S+$/.test(normalized)) throw new Error("valid email is required");
    if (password.length < 12) throw new Error("password must contain at least 12 characters");
    if (await this.store.findByEmail(normalized)) throw new Error("email already registered");
    return publicUser(await this.store.createUser({ email: normalized, passwordDigest: passwordDigest(password), role: "user" }));
  }

  async login(email: string, password: string): Promise<AuthSession> {
    const user = await this.store.findByEmail(email.trim().toLowerCase());
    if (user === undefined || !verifyPassword(password, user.passwordDigest)) throw new Error("invalid credentials");
    const session: AuthSession = { token: randomBytes(32).toString("base64url"), userId: user.id, expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000 };
    await this.store.createSession(session);
    return { ...session };
  }

  async authenticate(token: string | undefined): Promise<UserAccount | undefined> {
    if (!token) return undefined;
    const session = await this.store.findSession(token);
    if (session === undefined || session.expiresAt <= Date.now()) {
      if (session !== undefined) await this.store.revokeSession(token);
      return undefined;
    }
    const user = await this.store.findById(session.userId);
    return user === undefined ? undefined : publicUser(user);
  }

  async revoke(token: string): Promise<void> { await this.store.revokeSession(token); }
  async listUsers(): Promise<UserAccount[]> { return (await this.store.listUsers()).map(publicUser); }

  async seedAdmin(email: string, password: string): Promise<UserAccount> {
    const normalized = email.trim().toLowerCase();
    const existing = await this.store.findByEmail(normalized);
    const user = existing ?? await this.store.createUser({ email: normalized, passwordDigest: passwordDigest(password), role: "platform_admin" });
    if (existing && existing.role !== "platform_admin") throw new Error("bootstrap admin email belongs to a normal user");
    return publicUser(user);
  }
}
