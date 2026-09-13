import { randomUUID } from "node:crypto";
import type { PaymentOrder, PaymentStatus } from "@muse/core";
import type { DatabaseClient } from "./index.js";

export interface PaymentOrderPort {
  create(input: { accountId: string; planId: string; priceId: string; amountFen: number; provider: PaymentOrder["provider"] }): Promise<PaymentOrder>;
  get(id: string): Promise<PaymentOrder | undefined>;
  list(accountId?: string): Promise<PaymentOrder[]>;
  applyProviderEvent(input: { idempotencyKey: string; providerOrderId: string; status: "paid" | "refunded" }): Promise<PaymentOrder>;
}

function mapOrder(row: { id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }): PaymentOrder {
  return {
    id: row.id,
    accountId: row.user_id,
    planId: row.plan_id,
    priceId: row.price_id,
    amountFen: Number(row.amount_fen),
    currency: "CNY",
    provider: row.provider,
    providerOrderId: row.provider_order_id,
    status: row.status,
    createdAt: new Date(row.created_at).toISOString(),
    ...(row.paid_at === null ? {} : { paidAt: new Date(row.paid_at).toISOString() }),
    ...(row.refunded_at === null ? {} : { refundedAt: new Date(row.refunded_at).toISOString() }),
  };
}

/** PostgreSQL payment order repository; webhook transitions are guarded by row locks and idempotency keys. */
export class PostgresPaymentOrderRepository implements PaymentOrderPort {
  constructor(private readonly db: DatabaseClient) {}

  async create(input: { accountId: string; planId: string; priceId: string; amountFen: number; provider: PaymentOrder["provider"] }): Promise<PaymentOrder> {
    if (!Number.isSafeInteger(input.amountFen) || input.amountFen < 0) throw new Error("amountFen must be a non-negative integer");
    const id = `ord_${randomUUID()}`;
    const rows = await this.db.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>(
      "insert into payment_orders(id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id) values ($1, $2, $3, $4, $5, 'CNY', $6, $7) returning id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at",
      [id, input.accountId, input.planId, input.priceId, input.amountFen, input.provider, `${input.provider}_${id}`],
    );
    const row = rows[0];
    if (!row) throw new Error("payment order insert returned no row");
    return mapOrder(row);
  }

  async get(id: string): Promise<PaymentOrder | undefined> {
    const rows = await this.db.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>("select id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at from payment_orders where id = $1", [id]);
    return rows[0] ? mapOrder(rows[0]) : undefined;
  }

  async list(accountId?: string): Promise<PaymentOrder[]> {
    const rows = accountId === undefined
      ? await this.db.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>("select id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at from payment_orders order by created_at desc")
      : await this.db.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>("select id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at from payment_orders where user_id = $1 order by created_at desc", [accountId]);
    return rows.map(mapOrder);
  }

  async applyProviderEvent(input: { idempotencyKey: string; providerOrderId: string; status: "paid" | "refunded" }): Promise<PaymentOrder> {
    if (!input.idempotencyKey) throw new Error("idempotencyKey is required");
    return this.db.transaction(async (transaction) => {
      const prior = await transaction.query<{ provider_order_id: string; status: PaymentStatus }>("select provider_order_id, status from payment_webhook_events where idempotency_key = $1", [input.idempotencyKey]);
      if (prior[0]) {
        const existing = await transaction.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>("select id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at from payment_orders where provider_order_id = $1", [input.providerOrderId]);
        if (!existing[0]) throw new Error("payment order not found");
        return mapOrder(existing[0]);
      }
      const rows = await transaction.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>("select id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at from payment_orders where provider_order_id = $1 for update", [input.providerOrderId]);
      const row = rows[0];
      if (!row) throw new Error("payment order not found");
      if (input.status === "paid" && row.status !== "pending" && row.status !== "paid") throw new Error(`cannot mark ${row.status} order paid`);
      if (input.status === "refunded" && row.status !== "paid" && row.status !== "refunded") throw new Error(`cannot refund ${row.status} order`);
      const nextStatus = input.status;
      await transaction.query("update payment_orders set status = $1, paid_at = case when $1 = 'paid' then coalesce(paid_at, now()) else paid_at end, refunded_at = case when $1 = 'refunded' then coalesce(refunded_at, now()) else refunded_at end where id = $2", [nextStatus, row.id]);
      await transaction.query("insert into payment_webhook_events(idempotency_key, provider_order_id, status) values ($1, $2, $3)", [input.idempotencyKey, input.providerOrderId, input.status]);
      const updated = await transaction.query<{ id: string; user_id: string; plan_id: string; price_id: string; amount_fen: string | number; currency: "CNY"; provider: PaymentOrder["provider"]; provider_order_id: string; status: PaymentStatus; created_at: string; paid_at: string | null; refunded_at: string | null }>("select id, user_id, plan_id, price_id, amount_fen, currency, provider, provider_order_id, status, created_at, paid_at, refunded_at from payment_orders where id = $1", [row.id]);
      if (!updated[0]) throw new Error("payment order update returned no row");
      return mapOrder(updated[0]);
    });
  }
}
