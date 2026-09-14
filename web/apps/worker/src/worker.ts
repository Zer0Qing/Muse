import { PostgresDatabaseClient, PostgresJobQueue, applyMigrations, migrations, type PgPoolLike } from "@muse/database";
import { InMemoryJobQueue, JobWorker, RedisJobQueue, type JobQueue } from "@muse/jobs";

const intervalMs = Math.max(250, Number(process.env.WORKER_POLL_INTERVAL_MS ?? 5000));
const workerId = process.env.WORKER_ID ?? `worker-${process.pid}`;
type ClosableQueue = JobQueue & { close: () => Promise<void> };

function withClose(queue: JobQueue, close: () => Promise<void>): ClosableQueue {
  return { enqueue: queue.enqueue.bind(queue), claim: queue.claim.bind(queue), complete: queue.complete.bind(queue), fail: queue.fail.bind(queue), close };
}

async function createQueue(): Promise<ClosableQueue> {
  const redisUrl = process.env.REDIS_URL;
  if (redisUrl) {
    const redis = await import("redis");
    const client = redis.createClient({ url: redisUrl });
    await client.connect();
    return withClose(new RedisJobQueue(client), async () => { await client.quit(); });
  }
  const databaseUrl = process.env.DATABASE_URL;
  if (databaseUrl) {
    const pg = await import("pg");
    const pool = new pg.Pool({ connectionString: databaseUrl, max: Number(process.env.DB_POOL_MAX ?? 5), idleTimeoutMillis: 30_000, connectionTimeoutMillis: 10_000 });
    const database = new PostgresDatabaseClient(pool as unknown as PgPoolLike);
    await applyMigrations(database, migrations);
    return withClose(new PostgresJobQueue(database), async () => { await pool.end(); });
  }
  return withClose(new InMemoryJobQueue(), async () => {});
}

function skip(job: { id: string; type: string }): void {
  // F-42: 占位实现 — 需后端服务提供方实现;保持无副作用,仅记日志后跳过,避免误完成/误失败。
  console.warn(`[muse-worker] ${job.type} handler is a no-op placeholder; job ${job.id} skipped (requires backend service provider)`);
}

const queue = await createQueue();

const worker = new JobWorker(queue, {
  // F-42: memory_extract / rag_index 在 Android 运行时(Host 模式)由 muse App 闭环实现;
  // web worker 侧暂无对应后端服务,占位跳过。TODO(backend): 接入记忆抽取 / RAG 索引服务。
  memory_extract: async (job) => skip(job),
  rag_index: async (job) => skip(job),
  // TODO(backend): 接入文档解析服务(上传 → 提取正文)。
  document_parse: async (job) => skip(job),
  // TODO(backend): 接入媒体轮询服务。
  media_poll: async (job) => skip(job),
  // TODO(backend): 接入通知投递服务。
  notification: async (job) => skip(job),
});
console.log(`Muse worker started; poll interval=${intervalMs}ms; id=${workerId}; queue=${process.env.REDIS_URL ? "redis" : process.env.DATABASE_URL ? "postgres" : "memory"}`);
let stopping = false;
const timer = setInterval(() => { if (!stopping) void worker.runOnce(workerId).catch((error) => console.error("worker iteration failed", error)); }, intervalMs);
async function shutdown(): Promise<void> { if (stopping) return; stopping = true; clearInterval(timer); await queue.close(); process.exit(0); }
process.once("SIGTERM", () => { void shutdown(); });
process.once("SIGINT", () => { void shutdown(); });
