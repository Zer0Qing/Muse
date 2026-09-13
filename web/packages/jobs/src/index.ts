export type JobType = "memory_extract" | "rag_index" | "document_parse" | "media_poll" | "notification";
export type JobStatus = "queued" | "running" | "completed" | "failed" | "dead";

export interface JobRecord {
  readonly id: string;
  readonly accountId: string;
  readonly type: JobType;
  readonly payload: Readonly<Record<string, unknown>>;
  readonly status: JobStatus;
  readonly attempts: number;
  readonly maxAttempts: number;
  readonly runAfter: number;
  readonly idempotencyKey: string;
  readonly error?: string;
}

export interface JobQueue {
  enqueue(input: { accountId: string; type: JobType; payload: Readonly<Record<string, unknown>>; idempotencyKey: string; maxAttempts?: number; runAfter?: number }): Promise<JobRecord>;
  claim(workerId: string, now?: number): Promise<JobRecord | undefined>;
  complete(jobId: string): Promise<void>;
  fail(jobId: string, error: string, now?: number): Promise<JobRecord | undefined>;
}

/** Deterministic in-memory queue for local development and worker tests. */
export class InMemoryJobQueue implements JobQueue {
  private readonly jobs = new Map<string, JobRecord>();
  private readonly idempotency = new Map<string, string>();

  async enqueue(input: { accountId: string; type: JobType; payload: Readonly<Record<string, unknown>>; idempotencyKey: string; maxAttempts?: number; runAfter?: number }): Promise<JobRecord> {
    if (!input.accountId || !input.idempotencyKey) throw new Error("accountId and idempotencyKey are required");
    const existingId = this.idempotency.get(input.idempotencyKey);
    if (existingId) { const existing = this.jobs.get(existingId); if (existing) return { ...existing, payload: { ...existing.payload } }; }
    const job: JobRecord = { id: crypto.randomUUID(), accountId: input.accountId, type: input.type, payload: { ...input.payload }, status: "queued", attempts: 0, maxAttempts: Math.max(1, Math.min(10, input.maxAttempts ?? 3)), runAfter: input.runAfter ?? Date.now(), idempotencyKey: input.idempotencyKey };
    this.jobs.set(job.id, job); this.idempotency.set(job.idempotencyKey, job.id); return { ...job, payload: { ...job.payload } };
  }

  async claim(_workerId: string, now = Date.now()): Promise<JobRecord | undefined> {
    const job = [...this.jobs.values()].filter((item) => item.status === "queued" && item.runAfter <= now).sort((a, b) => a.runAfter - b.runAfter)[0];
    if (!job) return undefined;
    const claimed: JobRecord = { ...job, status: "running", attempts: job.attempts + 1 };
    this.jobs.set(job.id, claimed); return { ...claimed, payload: { ...claimed.payload } };
  }

  async complete(jobId: string): Promise<void> { const job = this.jobs.get(jobId); if (!job) throw new Error("job not found"); if (job.status !== "running") throw new Error("only running jobs can complete"); this.jobs.set(jobId, { ...job, status: "completed" }); }

  async fail(jobId: string, error: string, now = Date.now()): Promise<JobRecord | undefined> {
    const job = this.jobs.get(jobId); if (!job) return undefined;
    const dead = job.attempts >= job.maxAttempts;
    const updated: JobRecord = { ...job, status: dead ? "dead" : "queued", runAfter: dead ? job.runAfter : now + Math.min(60_000, 1000 * 2 ** job.attempts), error: error.slice(0, 500) };
    this.jobs.set(jobId, updated); return { ...updated, payload: { ...updated.payload } };
  }
}

export interface RedisCommandClient {
  sendCommand(command: string[]): Promise<unknown>;
  connect?(): Promise<unknown>;
  quit?(): Promise<unknown>;
}

function redisJobKey(id: string): string { return `muse:job:${id}`; }
const REDIS_QUEUE_KEY = "muse:jobs:ready";

/** Redis queue implementation; claim uses a Lua script so multiple workers cannot take one job. */
export class RedisJobQueue implements JobQueue {
  constructor(private readonly redis: RedisCommandClient) {}

  async enqueue(input: { accountId: string; type: JobType; payload: Readonly<Record<string, unknown>>; idempotencyKey: string; maxAttempts?: number; runAfter?: number }): Promise<JobRecord> {
    if (!input.accountId || !input.idempotencyKey) throw new Error("accountId and idempotencyKey are required");
    const id = crypto.randomUUID();
    const now = input.runAfter ?? Date.now();
    const job: JobRecord = { id, accountId: input.accountId, type: input.type, payload: { ...input.payload }, status: "queued", attempts: 0, maxAttempts: Math.max(1, Math.min(10, input.maxAttempts ?? 3)), runAfter: now, idempotencyKey: input.idempotencyKey };
    const result = await this.redis.sendCommand(["EVAL", `local existing = redis.call('GET', KEYS[2]); if existing then return existing end; redis.call('SET', KEYS[2], ARGV[1]); redis.call('HSET', KEYS[1], 'json', ARGV[2]); redis.call('ZADD', KEYS[3], ARGV[3], ARGV[1]); return ARGV[1]`, "3", redisJobKey(id), `muse:job:idempotency:${input.idempotencyKey}`, REDIS_QUEUE_KEY, id, JSON.stringify(job), String(now)]);
    const resolvedId = String(result);
    if (resolvedId !== id) {
      const existing = await this.redis.sendCommand(["HGET", redisJobKey(resolvedId), "json"]);
      if (typeof existing !== "string") throw new Error("existing job payload is missing");
      return JSON.parse(existing) as JobRecord;
    }
    return { ...job, payload: { ...job.payload } };
  }

  async claim(workerId: string, now = Date.now()): Promise<JobRecord | undefined> {
    const result = await this.redis.sendCommand(["EVAL", `local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 1); if #ids == 0 then return false end; local id = ids[1]; local key = ARGV[2] .. id; local raw = redis.call('HGET', key, 'json'); if not raw then redis.call('ZREM', KEYS[1], id); return false end; local job = cjson.decode(raw); if job.status ~= 'queued' then redis.call('ZREM', KEYS[1], id); return false end; job.status = 'running'; job.attempts = job.attempts + 1; job.lockedBy = ARGV[3]; redis.call('HSET', key, 'json', cjson.encode(job)); redis.call('ZREM', KEYS[1], id); return cjson.encode(job)`, "1", REDIS_QUEUE_KEY, String(now), "muse:job:", workerId]);
    if (result === false || result === null) return undefined;
    return JSON.parse(String(result)) as JobRecord;
  }

  async complete(jobId: string): Promise<void> {
    const raw = await this.redis.sendCommand(["HGET", redisJobKey(jobId), "json"]);
    if (typeof raw !== "string") throw new Error("job not found");
    const job = JSON.parse(raw) as JobRecord;
    if (job.status !== "running") throw new Error("only running jobs can complete");
    await this.redis.sendCommand(["HSET", redisJobKey(jobId), "json", JSON.stringify({ ...job, status: "completed" })]);
  }

  async fail(jobId: string, error: string, now = Date.now()): Promise<JobRecord | undefined> {
    const raw = await this.redis.sendCommand(["HGET", redisJobKey(jobId), "json"]);
    if (typeof raw !== "string") return undefined;
    const job = JSON.parse(raw) as JobRecord;
    const dead = job.attempts >= job.maxAttempts;
    const updated: JobRecord = { ...job, status: dead ? "dead" : "queued", runAfter: dead ? job.runAfter : now + Math.min(60_000, 1000 * 2 ** job.attempts), error: error.slice(0, 500) };
    await this.redis.sendCommand(["HSET", redisJobKey(jobId), "json", JSON.stringify(updated)]);
    if (!dead) await this.redis.sendCommand(["ZADD", REDIS_QUEUE_KEY, String(updated.runAfter), jobId]);
    return updated;
  }
}

export class JobWorker {
  constructor(private readonly queue: JobQueue, private readonly handlers: Partial<Record<JobType, (job: JobRecord) => Promise<void>>>) {}
  async runOnce(workerId: string): Promise<JobRecord | undefined> {
    const job = await this.queue.claim(workerId);
    if (!job) return undefined;
    const handler = this.handlers[job.type];
    if (!handler) { await this.queue.fail(job.id, `no handler for job type ${job.type}`); return job; }
    try { await handler(job); await this.queue.complete(job.id); } catch (error) { await this.queue.fail(job.id, error instanceof Error ? error.message : "job failed"); }
    return job;
  }
}
