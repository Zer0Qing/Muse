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

const queue = await createQueue();
const worker = new JobWorker(queue, {
  memory_extract: async () => {},
  rag_index: async () => {},
  document_parse: async () => {},
  media_poll: async () => {},
  notification: async () => {},
});
console.log(`Muse worker started; poll interval=${intervalMs}ms; id=${workerId}; queue=${process.env.REDIS_URL ? "redis" : process.env.DATABASE_URL ? "postgres" : "memory"}`);
let stopping = false;
const timer = setInterval(() => { if (!stopping) void worker.runOnce(workerId).catch((error) => console.error("worker iteration failed", error)); }, intervalMs);
async function shutdown(): Promise<void> { if (stopping) return; stopping = true; clearInterval(timer); await queue.close(); process.exit(0); }
process.once("SIGTERM", () => { void shutdown(); });
process.once("SIGINT", () => { void shutdown(); });
