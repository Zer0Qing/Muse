import { randomUUID } from "node:crypto";
import type { BillingPlan, CreditBalance, CreditEntry, PlanPriceVersion } from "@muse/core";
import type { DatabaseClient } from "./index.js";

/** PostgreSQL billing implementation with transaction-safe credit consumption. */
export class PostgresBillingService {
  constructor(private readonly db: DatabaseClient) {}

  async listPlans(includeArchived = false): Promise<BillingPlan[]> {
    const rows = await this.db.query<{ id: string; name: string; currency: "CNY"; included_credits: string | number; byok_allowed: boolean; hosted_models_allowed: boolean; limits_json: Record<string, number>; archived: boolean }>(
      `select id, name, currency, included_credits, byok_allowed, hosted_models_allowed, limits_json, archived from plans ${includeArchived ? "" : "where archived = false"} order by id`,
    );
    const result: BillingPlan[] = [];
    for (const row of rows) {
      const prices = await this.db.query<{ id: string; monthly_price_fen: string | number; annual_price_fen: string | number; effective_at: string; created_at: string; created_by: string }>(
        "select id, monthly_price_fen, annual_price_fen, effective_at, created_at, created_by from plan_prices where plan_id = $1 order by effective_at",
        [row.id],
      );
      result.push({
        id: row.id,
        name: row.name,
        currency: "CNY",
        includedCredits: Number(row.included_credits),
        byokAllowed: row.byok_allowed,
        hostedModelsAllowed: row.hosted_models_allowed,
        limits: row.limits_json ?? {},
        archived: row.archived,
        prices: prices.map((price) => ({ id: price.id, monthlyPriceFen: Number(price.monthly_price_fen), annualPriceFen: Number(price.annual_price_fen), effectiveAt: new Date(price.effective_at).toISOString(), createdAt: new Date(price.created_at).toISOString(), createdBy: price.created_by })),
      });
    }
    return result;
  }

  async getPlan(id: string): Promise<BillingPlan | undefined> {
    return (await this.listPlans(true)).find((plan) => plan.id === id);
  }

  async currentPrice(planId: string, at = new Date()): Promise<PlanPriceVersion> {
    const rows = await this.db.query<{ id: string; monthly_price_fen: string | number; annual_price_fen: string | number; effective_at: string; created_at: string; created_by: string }>(
      "select id, monthly_price_fen, annual_price_fen, effective_at, created_at, created_by from plan_prices where plan_id = $1 and effective_at <= $2 order by effective_at desc limit 1",
      [planId, at.toISOString()],
    );
    const price = rows[0];
    if (!price) throw new Error("plan has no effective price");
    return { id: price.id, monthlyPriceFen: Number(price.monthly_price_fen), annualPriceFen: Number(price.annual_price_fen), effectiveAt: new Date(price.effective_at).toISOString(), createdAt: new Date(price.created_at).toISOString(), createdBy: price.created_by };
  }

  async planFor(accountId: string): Promise<BillingPlan> {
    const rows = await this.db.query<{ plan_id: string }>("select plan_id from account_plans where user_id = $1", [accountId]);
    return (await this.getPlan(rows[0]?.plan_id ?? "free")) ?? (() => { throw new Error("billing plan not found"); })();
  }

  async assignPlan(accountId: string, planId: string, grantIncludedCredits = true): Promise<BillingPlan> {
    const plan = await this.getPlan(planId);
    if (!plan || plan.archived) throw new Error("plan not found or archived");
    await this.db.transaction(async (transaction) => {
      await transaction.query("insert into account_plans(user_id, plan_id) values ($1, $2) on conflict (user_id) do update set plan_id = excluded.plan_id, assigned_at = now()", [accountId, planId]);
      if (grantIncludedCredits && plan.includedCredits > 0) {
        await transaction.query("insert into credit_ledger(id, user_id, delta, reason, idempotency_key) values ($1, $2, $3, $4, $5) on conflict (idempotency_key) do nothing", [randomUUID(), accountId, plan.includedCredits, `plan:${planId}`, `plan-grant:${accountId}:${planId}`]);
      }
    });
    return plan;
  }

  async authorize(accountId: string, mode: "byok" | "hosted"): Promise<BillingPlan> {
    const plan = await this.planFor(accountId);
    if (mode === "byok" && !plan.byokAllowed) throw new Error("current plan does not allow BYOK");
    if (mode === "hosted" && !plan.hostedModelsAllowed) throw new Error("current plan does not allow hosted models");
    return plan;
  }

  async grant(accountId: string, amount: number, reason: string, idempotencyKey: string): Promise<CreditEntry> {
    if (!Number.isSafeInteger(amount) || amount < 0) throw new Error("amount must be a non-negative safe integer");
    return this.append(accountId, amount, reason, idempotencyKey);
  }

  async consume(accountId: string, amount: number, reason: string, idempotencyKey: string): Promise<CreditEntry> {
    if (!Number.isSafeInteger(amount) || amount < 1) throw new Error("amount must be a positive safe integer");
    return this.db.transaction(async (transaction) => {
      const existing = await transaction.query<{ id: string; user_id: string; delta: string | number; reason: string; idempotency_key: string; created_at: string }>("select id, user_id, delta, reason, idempotency_key, created_at from credit_ledger where idempotency_key = $1", [idempotencyKey]);
      if (existing[0]) {
        const entry = existing[0];
        if (entry.user_id !== accountId || Number(entry.delta) !== -amount) throw new Error("idempotency key reuse conflict");
        return this.mapEntry(entry);
      }
      await transaction.query("select id from user_accounts where id = $1 for update", [accountId]);
      const balanceRows = await transaction.query<{ balance: string | number }>("select coalesce(sum(delta), 0) as balance from credit_ledger where user_id = $1", [accountId]);
      if (Number(balanceRows[0]?.balance ?? 0) < amount) throw new Error("insufficient credits");
      const rows = await transaction.query<{ id: string; user_id: string; delta: string | number; reason: string; idempotency_key: string; created_at: string }>("insert into credit_ledger(id, user_id, delta, reason, idempotency_key) values ($1, $2, $3, $4, $5) returning id, user_id, delta, reason, idempotency_key, created_at", [randomUUID(), accountId, -amount, reason, idempotencyKey]);
      const entry = rows[0];
      if (!entry) throw new Error("credit ledger insert returned no row");
      return this.mapEntry(entry);
    });
  }

  async balance(accountId: string): Promise<CreditBalance> {
    const rows = await this.db.query<{ balance: string | number }>("select coalesce(sum(delta), 0) as balance from credit_ledger where user_id = $1", [accountId]);
    return { accountId, balance: Number(rows[0]?.balance ?? 0), entries: [] };
  }

  async createPlan(input: { id: string; name: string; monthlyPriceFen: number; annualPriceFen: number; includedCredits: number; byokAllowed: boolean; hostedModelsAllowed: boolean; limits: Record<string, number>; createdBy: string }): Promise<BillingPlan> {
    const priceId = randomUUID();
    await this.db.transaction(async (transaction) => {
      await transaction.query("insert into plans(id, name, currency, included_credits, byok_allowed, hosted_models_allowed, limits_json) values ($1, $2, 'CNY', $3, $4, $5, $6)", [input.id, input.name, input.includedCredits, input.byokAllowed, input.hostedModelsAllowed, JSON.stringify(input.limits)]);
      await transaction.query("insert into plan_prices(id, plan_id, monthly_price_fen, annual_price_fen, effective_at, created_by) values ($1, $2, $3, $4, now(), $5)", [priceId, input.id, input.monthlyPriceFen, input.annualPriceFen, input.createdBy]);
    });
    const plan = await this.getPlan(input.id);
    if (!plan) throw new Error("created plan cannot be loaded");
    return plan;
  }

  async schedulePriceChange(input: { planId: string; monthlyPriceFen: number; annualPriceFen: number; effectiveAt: string; createdBy: string; idempotencyKey: string }): Promise<BillingPlan> {
    const existing = await this.db.query<{ id: string }>("select id from plan_prices where idempotency_key = $1", [input.idempotencyKey]);
    if (existing[0]) { const plan = await this.getPlan(input.planId); if (!plan) throw new Error("plan not found"); return plan; }
    await this.db.query("insert into plan_prices(id, plan_id, monthly_price_fen, annual_price_fen, effective_at, created_by, idempotency_key) values ($1, $2, $3, $4, $5, $6, $7)", [randomUUID(), input.planId, input.monthlyPriceFen, input.annualPriceFen, input.effectiveAt, input.createdBy, input.idempotencyKey]);
    const plan = await this.getPlan(input.planId);
    if (!plan) throw new Error("plan not found");
    return plan;
  }

  private async append(accountId: string, delta: number, reason: string, idempotencyKey: string): Promise<CreditEntry> {
    const rows = await this.db.query<{ id: string; user_id: string; delta: string | number; reason: string; idempotency_key: string; created_at: string }>("insert into credit_ledger(id, user_id, delta, reason, idempotency_key) values ($1, $2, $3, $4, $5) on conflict (idempotency_key) do update set idempotency_key = excluded.idempotency_key returning id, user_id, delta, reason, idempotency_key, created_at", [randomUUID(), accountId, delta, reason, idempotencyKey]);
    const entry = rows[0];
    if (!entry) throw new Error("credit ledger insert returned no row");
    return this.mapEntry(entry);
  }

  private mapEntry(row: { id: string; user_id: string; delta: string | number; reason: string; idempotency_key: string; created_at: string }): CreditEntry {
    return { id: row.id, accountId: row.user_id, delta: Number(row.delta), reason: row.reason, idempotencyKey: row.idempotency_key, createdAt: new Date(row.created_at).toISOString() };
  }
}
