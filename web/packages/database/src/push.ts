import { randomUUID } from "node:crypto";
import type { PushSubscriptionRecord } from "@muse/contracts";
import type { DatabaseClient } from "./index.js";

interface PushRow { id: string; user_id: string; endpoint: string; p256dh: string; auth: string; user_agent: string | null; created_at: string; last_seen_at: string; }
function map(row: PushRow): PushSubscriptionRecord { return { id: row.id, accountId: row.user_id, endpoint: row.endpoint, p256dh: row.p256dh, auth: row.auth, ...(row.user_agent === null ? {} : { userAgent: row.user_agent }), createdAt: new Date(row.created_at).toISOString(), lastSeenAt: new Date(row.last_seen_at).toISOString() }; }

/** Tenant-scoped Web Push subscription repository. */
export class PostgresPushRepository {
  constructor(private readonly db: DatabaseClient) {}
  async upsert(input: Omit<PushSubscriptionRecord, "id" | "createdAt" | "lastSeenAt">): Promise<PushSubscriptionRecord> {
    if (!input.accountId || !input.endpoint || !input.p256dh || !input.auth) throw new Error("push subscription fields are required");
    const rows = await this.db.query<PushRow>("insert into push_subscriptions(id, user_id, endpoint, p256dh, auth, user_agent) values ($1, $2, $3, $4, $5, $6) on conflict (user_id, endpoint) do update set p256dh = excluded.p256dh, auth = excluded.auth, user_agent = excluded.user_agent, last_seen_at = now() returning id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_seen_at", [randomUUID(), input.accountId, input.endpoint, input.p256dh, input.auth, input.userAgent ?? null]);
    if (!rows[0]) throw new Error("push subscription insert returned no row");
    return map(rows[0]);
  }
  async list(accountId: string): Promise<PushSubscriptionRecord[]> { const rows = await this.db.query<PushRow>("select id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_seen_at from push_subscriptions where user_id = $1 order by last_seen_at desc", [accountId]); return rows.map(map); }
  async remove(accountId: string, id: string): Promise<void> { await this.db.query("delete from push_subscriptions where user_id = $1 and id = $2", [accountId, id]); }
}
