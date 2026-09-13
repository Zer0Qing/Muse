import { randomUUID } from "node:crypto";
import type { JobQueue, JobRecord, JobType } from "@muse/jobs";
import type { DatabaseClient } from "./index.js";

interface JobRow { id: string; user_id: string; type: JobType; payload: Record<string, unknown>; status: JobRecord["status"]; attempts: number; max_attempts: number; run_after: string; idempotency_key: string; last_error: string | null; }
function map(row: JobRow): JobRecord { return { id: row.id, accountId: row.user_id, type: row.type, payload: row.payload, status: row.status, attempts: row.attempts, maxAttempts: row.max_attempts, runAfter: new Date(row.run_after).getTime(), idempotencyKey: row.idempotency_key, ...(row.last_error === null ? {} : { error: row.last_error }) }; }

/** PostgreSQL queue using SKIP LOCKED for multi-worker claims. */
export class PostgresJobQueue implements JobQueue {
  constructor(private readonly db: DatabaseClient) {}
  async enqueue(input: { accountId: string; type: JobType; payload: Readonly<Record<string, unknown>>; idempotencyKey: string; maxAttempts?: number; runAfter?: number }): Promise<JobRecord> {
    const rows = await this.db.query<JobRow>("insert into jobs(id, user_id, type, payload, max_attempts, run_after, idempotency_key) values ($1, $2, $3, $4, $5, $6, $7) on conflict (idempotency_key) do update set idempotency_key = excluded.idempotency_key returning id, user_id, type, payload, status, attempts, max_attempts, run_after, idempotency_key, last_error", [randomUUID(), input.accountId, input.type, JSON.stringify(input.payload), Math.max(1, Math.min(10, input.maxAttempts ?? 3)), new Date(input.runAfter ?? Date.now()).toISOString(), input.idempotencyKey]);
    const row = rows[0]; if (!row) throw new Error("job insert returned no row"); return map(row);
  }
  async claim(workerId: string): Promise<JobRecord | undefined> {
    const rows = await this.db.query<JobRow>("with candidate as (select id from jobs where status = 'queued' and run_after <= now() order by run_after, created_at for update skip locked limit 1) update jobs set status = 'running', attempts = attempts + 1, locked_by = $1, locked_at = now(), updated_at = now() where id in (select id from candidate) returning id, user_id, type, payload, status, attempts, max_attempts, run_after, idempotency_key, last_error", [workerId]);
    return rows[0] ? map(rows[0]) : undefined;
  }
  async complete(jobId: string): Promise<void> { const rows = await this.db.query("update jobs set status = 'completed', locked_by = null, locked_at = null, updated_at = now() where id = $1 and status = 'running' returning id", [jobId]); if (!rows[0]) throw new Error("running job not found"); }
  async fail(jobId: string, error: string): Promise<JobRecord | undefined> { const rows = await this.db.query<JobRow>("update jobs set status = case when attempts >= max_attempts then 'dead' else 'queued' end, run_after = case when attempts >= max_attempts then run_after else now() + least(interval '60 seconds', (interval '1 second' * power(2, attempts))) end, locked_by = null, locked_at = null, last_error = $2, updated_at = now() where id = $1 and status = 'running' returning id, user_id, type, payload, status, attempts, max_attempts, run_after, idempotency_key, last_error", [jobId, error.slice(0, 500)]); return rows[0] ? map(rows[0]) : undefined; }
}
