export type BillingCurrency = "CNY";

export interface PlanPriceVersion {
  readonly id: string;
  readonly monthlyPriceFen: number;
  readonly annualPriceFen: number;
  readonly effectiveAt: string;
  readonly createdAt: string;
  readonly createdBy: string;
}

export interface BillingPlan {
  readonly id: string;
  readonly name: string;
  readonly currency: BillingCurrency;
  readonly includedCredits: number;
  readonly byokAllowed: boolean;
  readonly hostedModelsAllowed: boolean;
  readonly limits: Readonly<Record<string, number>>;
  readonly prices: readonly PlanPriceVersion[];
  readonly archived: boolean;
}

export interface CreditEntry {
  readonly id: string;
  readonly accountId: string;
  readonly delta: number;
  readonly reason: string;
  readonly idempotencyKey: string;
  readonly createdAt: string;
}

export interface CreditBalance {
  readonly accountId: string;
  readonly balance: number;
  readonly entries: readonly CreditEntry[];
}

function requirePositiveInteger(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${field} must be a non-negative safe integer`);
}

function requirePrice(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${field} must be a non-negative integer number of fen`);
}

function clonePlan(plan: BillingPlan): BillingPlan {
  return { ...plan, limits: { ...plan.limits }, prices: plan.prices.map((price) => ({ ...price })) };
}

/** In-memory billing domain used by the API bootstrap and deterministic tests. */
export class BillingCatalog {
  private readonly plans = new Map<string, BillingPlan>();
  private readonly idempotency = new Map<string, CreditEntry>();
  private readonly entries = new Map<string, CreditEntry[]>();
  private readonly accountPlans = new Map<string, string>();

  constructor(initialPlans: readonly BillingPlan[] = defaultPlans()) {
    for (const plan of initialPlans) this.putPlan(plan);
  }

  listPlans(includeArchived = false): BillingPlan[] {
    return [...this.plans.values()]
      .filter((plan) => includeArchived || !plan.archived)
      .map(clonePlan);
  }

  getPlan(id: string): BillingPlan | undefined {
    const plan = this.plans.get(id);
    return plan === undefined ? undefined : clonePlan(plan);
  }

  planFor(accountId: string): BillingPlan {
    const plan = this.getPlan(this.accountPlans.get(accountId) ?? "free");
    if (plan === undefined) throw new Error("default billing plan is missing");
    return plan;
  }

  currentPrice(planId: string, at = new Date()): PlanPriceVersion {
    const plan = this.getPlan(planId);
    if (plan === undefined) throw new Error("plan not found");
    const timestamp = at.getTime();
    const effective = plan.prices
      .filter((price) => new Date(price.effectiveAt).getTime() <= timestamp)
      .sort((left, right) => new Date(left.effectiveAt).getTime() - new Date(right.effectiveAt).getTime());
    const price = effective.at(-1);
    if (price === undefined) throw new Error("plan has no effective price");
    return { ...price };
  }

  assignPlan(accountId: string, planId: string, grantIncludedCredits = true): BillingPlan {
    const plan = this.getPlan(planId);
    if (plan === undefined || plan.archived) throw new Error("plan not found or archived");
    this.accountPlans.set(accountId, planId);
    if (grantIncludedCredits && plan.includedCredits > 0) {
      this.grant(accountId, plan.includedCredits, `plan:${planId}`, `plan-grant:${accountId}:${planId}`);
    }
    return plan;
  }

  authorize(accountId: string, mode: "byok" | "hosted"): BillingPlan {
    const plan = this.planFor(accountId);
    if (mode === "byok" && !plan.byokAllowed) throw new Error("current plan does not allow BYOK");
    if (mode === "hosted" && !plan.hostedModelsAllowed) throw new Error("current plan does not allow hosted models");
    return plan;
  }

  createPlan(input: {
    readonly id: string;
    readonly name: string;
    readonly monthlyPriceFen: number;
    readonly annualPriceFen: number;
    readonly includedCredits: number;
    readonly byokAllowed: boolean;
    readonly hostedModelsAllowed: boolean;
    readonly limits?: Readonly<Record<string, number>>;
    readonly createdBy: string;
  }): BillingPlan {
    if (!/^[a-z0-9][a-z0-9_-]{1,63}$/.test(input.id)) throw new Error("plan id must be 2-64 lowercase characters");
    if (this.plans.has(input.id)) throw new Error("plan already exists");
    requirePrice(input.monthlyPriceFen, "monthlyPriceFen");
    requirePrice(input.annualPriceFen, "annualPriceFen");
    requirePositiveInteger(input.includedCredits, "includedCredits");
    const now = new Date().toISOString();
    const plan: BillingPlan = {
      id: input.id,
      name: input.name.trim().slice(0, 100),
      currency: "CNY",
      includedCredits: input.includedCredits,
      byokAllowed: input.byokAllowed,
      hostedModelsAllowed: input.hostedModelsAllowed,
      limits: { ...(input.limits ?? {}) },
      prices: [{
        id: `${input.id}-price-1`,
        monthlyPriceFen: input.monthlyPriceFen,
        annualPriceFen: input.annualPriceFen,
        effectiveAt: now,
        createdAt: now,
        createdBy: input.createdBy,
      }],
      archived: false,
    };
    this.putPlan(plan);
    return clonePlan(plan);
  }

  schedulePriceChange(input: {
    readonly planId: string;
    readonly monthlyPriceFen: number;
    readonly annualPriceFen: number;
    readonly effectiveAt: string;
    readonly createdBy: string;
    readonly idempotencyKey: string;
  }): BillingPlan {
    const plan = this.plans.get(input.planId);
    if (plan === undefined) throw new Error("plan not found");
    requirePrice(input.monthlyPriceFen, "monthlyPriceFen");
    requirePrice(input.annualPriceFen, "annualPriceFen");
    const effectiveAt = new Date(input.effectiveAt);
    if (Number.isNaN(effectiveAt.getTime())) throw new Error("effectiveAt must be an ISO timestamp");
    const existing = plan.prices.find((price) => price.id === input.idempotencyKey);
    if (existing !== undefined) return clonePlan(plan);
    const price: PlanPriceVersion = {
      id: input.idempotencyKey,
      monthlyPriceFen: input.monthlyPriceFen,
      annualPriceFen: input.annualPriceFen,
      effectiveAt: effectiveAt.toISOString(),
      createdAt: new Date().toISOString(),
      createdBy: input.createdBy,
    };
    const updated: BillingPlan = { ...plan, prices: [...plan.prices, price] };
    this.putPlan(updated);
    return clonePlan(updated);
  }

  grant(accountId: string, amount: number, reason: string, idempotencyKey: string): CreditEntry {
    return this.appendCredit(accountId, amount, reason, idempotencyKey);
  }

  consume(accountId: string, amount: number, reason: string, idempotencyKey: string): CreditEntry {
    requirePositiveInteger(amount, "amount");
    if (amount < 1) throw new Error("amount must be greater than zero");
    const balance = this.balance(accountId).balance;
    if (balance < amount) throw new Error("insufficient credits");
    return this.appendCredit(accountId, -amount, reason, idempotencyKey);
  }

  balance(accountId: string): CreditBalance {
    const entries = this.entries.get(accountId) ?? [];
    return {
      accountId,
      balance: entries.reduce((sum, entry) => sum + entry.delta, 0),
      entries: entries.map((entry) => ({ ...entry })),
    };
  }

  private appendCredit(accountId: string, delta: number, reason: string, idempotencyKey: string): CreditEntry {
    if (!accountId.trim()) throw new Error("accountId is required");
    if (!idempotencyKey.trim()) throw new Error("idempotencyKey is required");
    const existing = this.idempotency.get(idempotencyKey);
    if (existing !== undefined) {
      if (existing.accountId !== accountId || existing.delta !== delta) throw new Error("idempotency key reuse conflict");
      return { ...existing };
    }
    const entry: CreditEntry = {
      id: `${accountId}-${this.idempotency.size + 1}`,
      accountId,
      delta,
      reason: reason.trim().slice(0, 200),
      idempotencyKey,
      createdAt: new Date().toISOString(),
    };
    this.idempotency.set(idempotencyKey, entry);
    this.entries.set(accountId, [...(this.entries.get(accountId) ?? []), entry]);
    return { ...entry };
  }

  private putPlan(plan: BillingPlan): void {
    if (plan.currency !== "CNY") throw new Error("only CNY plans are supported");
    this.plans.set(plan.id, clonePlan(plan));
  }
}

function defaultPlans(): BillingPlan[] {
  const now = new Date().toISOString();
  return [
    {
      id: "free",
      name: "免费 BYOK",
      currency: "CNY",
      includedCredits: 0,
      byokAllowed: true,
      hostedModelsAllowed: false,
      limits: { storageBytes: 100 * 1024 * 1024, mcpServers: 2, agentRuns: 20 },
      prices: [{ id: "free-price-1", monthlyPriceFen: 0, annualPriceFen: 0, effectiveAt: now, createdAt: now, createdBy: "system" }],
      archived: false,
    },
    {
      id: "pro",
      name: "Muse Pro",
      currency: "CNY",
      includedCredits: 1000,
      byokAllowed: true,
      hostedModelsAllowed: true,
      limits: { storageBytes: 5 * 1024 * 1024 * 1024, mcpServers: 10, agentRuns: 500 },
      prices: [{ id: "pro-price-1", monthlyPriceFen: 1999, annualPriceFen: 19990, effectiveAt: now, createdAt: now, createdBy: "system" }],
      archived: false,
    },
  ];
}
