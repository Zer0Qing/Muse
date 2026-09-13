import {
  BillingCatalog,
  InMemorySessionRepository,
  PaymentOrderBook,
  InMemoryAssistantCatalog,
  type AssistantCatalog,
  type BillingPlan,
  type CreditBalance,
  type CreditEntry,
  type PaymentOrder,
  type PlanPriceVersion,
  type ProviderCatalog,
  type SessionRepository,
} from "@muse/core";
import {
  applyMigrations,
  migrations,
  PostgresBillingService,
  PostgresChatRepository,
  PostgresDatabaseClient,
  PostgresPaymentOrderRepository,
  PostgresAssistantCatalog,
  PostgresProviderCatalog,
  PostgresPushRepository,
  PostgresMemoryRepository,
  PostgresRagRepository,
  PostgresFileRepository,
  PostgresJobQueue,
  SecretVault,
  type DatabaseClient,
  type PaymentOrderPort,
} from "@muse/database";
import { AuthService, PostgresAuthStore } from "./auth.js";
import type { PushSubscriptionRecord } from "@muse/contracts";
import { MemoryStore, type Fact, type MemorySpace } from "@muse/memory";
import { chunkText, searchChunks, toCitations, type RagCitation, type TextChunk } from "@muse/rag";
import { createS3ObjectStorage, type ObjectStorage } from "@muse/storage";
import type { FileDescriptor } from "@muse/files";
import { InMemoryJobQueue, RedisJobQueue, type JobQueue } from "@muse/jobs";

export interface BillingPort {
  listPlans(includeArchived?: boolean): Promise<BillingPlan[]>;
  getPlan(id: string): Promise<BillingPlan | undefined>;
  currentPrice(planId: string, at?: Date): Promise<PlanPriceVersion>;
  assignPlan(accountId: string, planId: string, grantIncludedCredits?: boolean): Promise<BillingPlan>;
  authorize(accountId: string, mode: "byok" | "hosted"): Promise<BillingPlan>;
  grant(accountId: string, amount: number, reason: string, idempotencyKey: string): Promise<CreditEntry>;
  consume(accountId: string, amount: number, reason: string, idempotencyKey: string): Promise<CreditEntry>;
  balance(accountId: string): Promise<CreditBalance>;
  createPlan(input: { id: string; name: string; monthlyPriceFen: number; annualPriceFen: number; includedCredits: number; byokAllowed: boolean; hostedModelsAllowed: boolean; limits: Record<string, number>; createdBy: string }): Promise<BillingPlan>;
  schedulePriceChange(input: { planId: string; monthlyPriceFen: number; annualPriceFen: number; effectiveAt: string; createdBy: string; idempotencyKey: string }): Promise<BillingPlan>;
}

class InMemoryBillingPort implements BillingPort {
  constructor(private readonly catalog: BillingCatalog) {}
  async listPlans(includeArchived = false): Promise<BillingPlan[]> { return this.catalog.listPlans(includeArchived); }
  async getPlan(id: string): Promise<BillingPlan | undefined> { return this.catalog.getPlan(id); }
  async currentPrice(planId: string, at = new Date()): Promise<PlanPriceVersion> { return this.catalog.currentPrice(planId, at); }
  async assignPlan(accountId: string, planId: string, grantIncludedCredits = true): Promise<BillingPlan> { return this.catalog.assignPlan(accountId, planId, grantIncludedCredits); }
  async authorize(accountId: string, mode: "byok" | "hosted"): Promise<BillingPlan> { return this.catalog.authorize(accountId, mode); }
  async grant(accountId: string, amount: number, reason: string, idempotencyKey: string): Promise<CreditEntry> { return this.catalog.grant(accountId, amount, reason, idempotencyKey); }
  async consume(accountId: string, amount: number, reason: string, idempotencyKey: string): Promise<CreditEntry> { return this.catalog.consume(accountId, amount, reason, idempotencyKey); }
  async balance(accountId: string): Promise<CreditBalance> { return this.catalog.balance(accountId); }
  async createPlan(input: Parameters<BillingCatalog["createPlan"]>[0]): Promise<BillingPlan> { return this.catalog.createPlan(input); }
  async schedulePriceChange(input: Parameters<BillingCatalog["schedulePriceChange"]>[0]): Promise<BillingPlan> { return this.catalog.schedulePriceChange(input); }
}

class InMemoryPaymentPort implements PaymentOrderPort {
  constructor(private readonly book: PaymentOrderBook) {}
  async create(input: Parameters<PaymentOrderBook["create"]>[0]): Promise<PaymentOrder> { return this.book.create(input); }
  async get(id: string): Promise<PaymentOrder | undefined> { return this.book.get(id); }
  async list(accountId?: string): Promise<PaymentOrder[]> { return this.book.list(accountId); }
  async applyProviderEvent(input: Parameters<PaymentOrderBook["applyProviderEvent"]>[0]): Promise<PaymentOrder> { return this.book.applyProviderEvent(input); }
}

export interface MemoryPort {
  createSpace(accountId: string, name: string, scope?: string): Promise<MemorySpace>;
  addFact(input: Omit<Fact, "id" | "hitCount" | "createdAt" | "updatedAt">): Promise<Fact>;
  search(accountId: string, query: string, assistantId: string, spaceId: string, limit?: number): Promise<Fact[]>;
  markHit(accountId: string, id: string): Promise<Fact>;
}

class InMemoryMemoryPort implements MemoryPort {
  constructor(private readonly store: MemoryStore) {}
  async createSpace(accountId: string, name: string, scope = "main"): Promise<MemorySpace> { return this.store.createSpace(accountId, name, scope); }
  async addFact(input: Omit<Fact, "id" | "hitCount" | "createdAt" | "updatedAt">): Promise<Fact> { return this.store.addFact(input); }
  async search(accountId: string, query: string, assistantId: string, spaceId: string, limit = 20): Promise<Fact[]> { return this.store.search(accountId, query, assistantId, spaceId, limit); }
  async markHit(accountId: string, id: string): Promise<Fact> { const fact = this.store.markHit(id); if (fact.accountId !== accountId) throw new Error("memory fact not found"); return fact; }
}

export interface RagPort {
  index(accountId: string, documentId: string, text: string): Promise<TextChunk[]>;
  search(accountId: string, query: string, limit?: number): Promise<{ chunks: TextChunk[]; citations: RagCitation[] }>;
}

class InMemoryRagPort implements RagPort {
  private readonly chunks: TextChunk[] = [];
  async index(accountId: string, documentId: string, text: string): Promise<TextChunk[]> { const chunks = chunkText(accountId, documentId, text); this.chunks.push(...chunks); return chunks; }
  async search(accountId: string, query: string, limit = 8): Promise<{ chunks: TextChunk[]; citations: RagCitation[] }> { const chunks = searchChunks(this.chunks, query, accountId, limit); return { chunks, citations: toCitations(chunks) }; }
}

class PostgresRagPort implements RagPort {
  constructor(private readonly repository: PostgresRagRepository) {}
  async index(accountId: string, documentId: string, text: string): Promise<TextChunk[]> { const chunks = chunkText(accountId, documentId, text); await this.repository.saveChunks(chunks); return chunks; }
  async search(accountId: string, query: string, limit = 8): Promise<{ chunks: TextChunk[]; citations: RagCitation[] }> { const chunks = await this.repository.listChunks(accountId); const hits = searchChunks(chunks, query, accountId, limit); return { chunks: hits, citations: toCitations(hits) }; }
}

export interface FilePort {
  create(input: Omit<FileDescriptor, "createdAt">): Promise<FileDescriptor>;
  get(accountId: string, id: string): Promise<FileDescriptor | undefined>;
}

class InMemoryFilePort implements FilePort {
  private readonly files = new Map<string, FileDescriptor>();
  async create(input: Omit<FileDescriptor, "createdAt">): Promise<FileDescriptor> { const file = { ...input, createdAt: new Date().toISOString() }; this.files.set(`${file.accountId}:${file.id}`, file); return { ...file }; }
  async get(accountId: string, id: string): Promise<FileDescriptor | undefined> { const file = this.files.get(`${accountId}:${id}`); return file ? { ...file } : undefined; }
}

function createObjectStorage(): ObjectStorage {
  const endpoint = process.env.S3_ENDPOINT;
  const bucket = process.env.S3_BUCKET;
  const accessKeyId = process.env.S3_ACCESS_KEY;
  const secretAccessKey = process.env.S3_SECRET_KEY;
  if (endpoint && bucket && accessKeyId && secretAccessKey && secretAccessKey !== "replace-me") return createS3ObjectStorage({ endpoint, bucket, accessKeyId, secretAccessKey, region: process.env.S3_REGION ?? "us-east-1", forcePathStyle: process.env.S3_FORCE_PATH_STYLE !== "false" });
  return new InMemoryObjectStorage();
}

class InMemoryObjectStorage implements ObjectStorage {
  async createUploadUrl(input: { accountId: string; objectKey: string; mimeType: string; sizeBytes: number }) { return { url: `/local-upload/${encodeURIComponent(input.objectKey)}`, expiresAt: new Date(Date.now() + 900_000).toISOString() }; }
  async createDownloadUrl(input: { accountId: string; objectKey: string; downloadName: string }) { return { url: `/local-download/${encodeURIComponent(input.objectKey)}`, expiresAt: new Date(Date.now() + 900_000).toISOString() }; }
  async delete(_input: { accountId: string; objectKey: string }): Promise<void> {}
}

export interface PushPort {
  upsert(input: Omit<PushSubscriptionRecord, "id" | "createdAt" | "lastSeenAt">): Promise<PushSubscriptionRecord>;
  list(accountId: string): Promise<PushSubscriptionRecord[]>;
  remove(accountId: string, id: string): Promise<void>;
}

class InMemoryPushPort implements PushPort {
  private readonly values = new Map<string, PushSubscriptionRecord>();
  async upsert(input: Omit<PushSubscriptionRecord, "id" | "createdAt" | "lastSeenAt">): Promise<PushSubscriptionRecord> { const now = new Date().toISOString(); const old = [...this.values.values()].find((item) => item.accountId === input.accountId && item.endpoint === input.endpoint); const item = { ...input, id: old?.id ?? crypto.randomUUID(), createdAt: old?.createdAt ?? now, lastSeenAt: now }; this.values.set(item.id, item); return { ...item }; }
  async list(accountId: string): Promise<PushSubscriptionRecord[]> { return [...this.values.values()].filter((item) => item.accountId === accountId).map((item) => ({ ...item })); }
  async remove(accountId: string, id: string): Promise<void> { const item = this.values.get(id); if (item?.accountId === accountId) this.values.delete(id); }
}

type ManagedJobQueue = JobQueue & { redisQuit?: () => Promise<void> };

async function createRedisQueue(url: string): Promise<ManagedJobQueue> {
  const redis = await import("redis");
  const client = redis.createClient({ url });
  await client.connect();
  const queue = new RedisJobQueue(client) as ManagedJobQueue;
  queue.redisQuit = async () => { await client.quit(); };
  return queue;
}

export interface ApiRuntime {
  readonly auth: AuthService;
  readonly sessions: SessionRepository;
  readonly billing: BillingPort;
  readonly paymentOrders: PaymentOrderPort;
  readonly assistants: AssistantCatalog;
  readonly providers: ProviderCatalog;
  readonly memory: MemoryPort;
  readonly rag: RagPort;
  readonly push: PushPort;
  readonly jobs: ManagedJobQueue;
  readonly storage: ObjectStorage;
  readonly files: FilePort;
  readonly database?: DatabaseClient;
  readonly close: () => Promise<void>;
}

/** Build one API dependency graph; DATABASE_URL switches all durable repositories together. */
export async function createApiRuntime(): Promise<ApiRuntime> {
  const databaseUrl = process.env.DATABASE_URL;
  const redisUrl = process.env.REDIS_URL;
  if (!databaseUrl) {
    const jobs: ManagedJobQueue = redisUrl ? await createRedisQueue(redisUrl) : new InMemoryJobQueue();
    return { auth: new AuthService(), sessions: new InMemorySessionRepository(), billing: new InMemoryBillingPort(new BillingCatalog()), paymentOrders: new InMemoryPaymentPort(new PaymentOrderBook()), assistants: new InMemoryAssistantCatalog(), providers: new (await import("@muse/core")).InMemoryProviderCatalog(), memory: new InMemoryMemoryPort(new MemoryStore()), rag: new InMemoryRagPort(), push: new InMemoryPushPort(), jobs, storage: createObjectStorage(), files: new InMemoryFilePort(), close: async () => { await jobs.redisQuit?.(); } };
  }
  const pg = await import("pg");
  const pool = new pg.Pool({ connectionString: databaseUrl, max: Number(process.env.DB_POOL_MAX ?? 10), idleTimeoutMillis: 30_000, connectionTimeoutMillis: 10_000 });
  const database = new PostgresDatabaseClient(pool);
  await applyMigrations(database, migrations);
  const jobs: ManagedJobQueue = redisUrl ? await createRedisQueue(redisUrl) : new PostgresJobQueue(database);
  const secretsMasterKey = process.env.SECRETS_MASTER_KEY;
  if (!secretsMasterKey || secretsMasterKey.length < 32) throw new Error("SECRETS_MASTER_KEY is required when DATABASE_URL is configured");
  return { auth: new AuthService(new PostgresAuthStore(database)), sessions: new PostgresChatRepository(database), billing: new PostgresBillingService(database), paymentOrders: new PostgresPaymentOrderRepository(database), assistants: new PostgresAssistantCatalog(database), providers: new PostgresProviderCatalog(database, new SecretVault(secretsMasterKey)), memory: new PostgresMemoryRepository(database), rag: new PostgresRagPort(new PostgresRagRepository(database)), push: new PostgresPushRepository(database), jobs, storage: createObjectStorage(), files: new PostgresFileRepository(database), database, close: async () => { await jobs.redisQuit?.(); await pool.end(); } };
}
