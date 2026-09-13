export type PaymentStatus = "pending" | "paid" | "failed" | "refunded" | "cancelled";

export interface PaymentOrder {
  readonly id: string;
  readonly accountId: string;
  readonly planId: string;
  readonly priceId: string;
  readonly amountFen: number;
  readonly currency: "CNY";
  readonly provider: "mock" | "alipay";
  readonly providerOrderId: string;
  readonly status: PaymentStatus;
  readonly createdAt: string;
  readonly paidAt?: string;
  readonly refundedAt?: string;
}

export interface PaymentCheckout {
  readonly order: PaymentOrder;
  readonly checkoutUrl: string;
}

export interface PaymentProvider {
  readonly id: PaymentOrder["provider"];
  createCheckout(order: PaymentOrder): Promise<PaymentCheckout>;
  verifyWebhook(payload: string, signature: string | undefined): Promise<{ providerOrderId: string; status: "paid" | "refunded" }>;
}

export interface AlipayConfig {
  readonly appId: string;
  readonly privateKeyPem: string;
  readonly alipayPublicKeyPem: string;
  readonly gatewayUrl: string;
  readonly notifyUrl: string;
  readonly returnUrl: string;
}

function canonicalParams(params: Readonly<Record<string, string>>): string {
  return Object.keys(params).filter((key) => key !== "sign" && key !== "sign_type" && params[key] !== "").sort().map((key) => `${key}=${params[key]}`).join("&");
}

/** Alipay computer-page payment adapter. It signs server-side and verifies async notifications with RSA2. */
export class AlipayPaymentProvider implements PaymentProvider {
  readonly id = "alipay" as const;
  constructor(private readonly config: AlipayConfig) {
    if (!config.appId || !config.privateKeyPem || !config.alipayPublicKeyPem || !config.notifyUrl || !config.returnUrl) throw new Error("incomplete Alipay configuration");
  }

  async createCheckout(order: PaymentOrder): Promise<PaymentCheckout> {
    const params: Record<string, string> = {
      app_id: this.config.appId,
      method: "alipay.trade.page.pay",
      format: "JSON",
      return_url: this.config.returnUrl,
      charset: "utf-8",
      sign_type: "RSA2",
      timestamp: new Date().toISOString().replace("T", " ").slice(0, 19),
      version: "1.0",
      notify_url: this.config.notifyUrl,
      biz_content: JSON.stringify({ out_trade_no: order.id, product_code: "FAST_INSTANT_TRADE_PAY", total_amount: (order.amountFen / 100).toFixed(2), subject: `Muse ${order.planId}` }),
    };
    const { createSign } = await import("node:crypto");
    const signer = createSign("RSA-SHA256");
    signer.update(canonicalParams(params), "utf8");
    params.sign = signer.sign(this.config.privateKeyPem, "base64");
    const query = new URLSearchParams(params).toString();
    return { order: { ...order }, checkoutUrl: `${this.config.gatewayUrl}?${query}` };
  }

  async verifyWebhook(payload: string, signature: string | undefined): Promise<{ providerOrderId: string; status: "paid" | "refunded" }> {
    const params = Object.fromEntries(new URLSearchParams(payload).entries());
    const receivedSign = signature ?? params.sign;
    if (!receivedSign) throw new Error("Alipay notification signature is missing");
    const { createVerify } = await import("node:crypto");
    const verifier = createVerify("RSA-SHA256");
    verifier.update(canonicalParams(params), "utf8");
    if (!verifier.verify(this.config.alipayPublicKeyPem, receivedSign, "base64")) throw new Error("invalid Alipay notification signature");
    const providerOrderId = params.out_trade_no;
    const tradeStatus = params.trade_status;
    if (!providerOrderId || !tradeStatus) throw new Error("invalid Alipay notification payload");
    if (tradeStatus === "TRADE_SUCCESS" || tradeStatus === "TRADE_FINISHED") return { providerOrderId, status: "paid" };
    if (tradeStatus === "TRADE_CLOSED") return { providerOrderId, status: "refunded" };
    throw new Error(`unsupported Alipay trade status: ${tradeStatus}`);
  }
}

function assertAmount(amountFen: number): void {
  if (!Number.isSafeInteger(amountFen) || amountFen < 0) throw new Error("amountFen must be a non-negative integer");
}

/** Payment state machine shared by mock/sandbox and the future Alipay adapter. */
export class PaymentOrderBook {
  private readonly orders = new Map<string, PaymentOrder>();
  private readonly webhookKeys = new Set<string>();
  private sequence = 0;

  create(input: { readonly accountId: string; readonly planId: string; readonly priceId: string; readonly amountFen: number; readonly provider: PaymentOrder["provider"] }): PaymentOrder {
    assertAmount(input.amountFen);
    if (!input.accountId || !input.planId || !input.priceId) throw new Error("payment order fields are required");
    const now = new Date().toISOString();
    const order: PaymentOrder = {
      id: `ord_${++this.sequence}`,
      accountId: input.accountId,
      planId: input.planId,
      priceId: input.priceId,
      amountFen: input.amountFen,
      currency: "CNY",
      provider: input.provider,
      providerOrderId: `${input.provider}_${this.sequence}`,
      status: "pending",
      createdAt: now,
    };
    this.orders.set(order.id, order);
    return { ...order };
  }

  get(id: string): PaymentOrder | undefined {
    const order = this.orders.get(id);
    return order === undefined ? undefined : { ...order };
  }

  list(accountId?: string): PaymentOrder[] {
    return [...this.orders.values()].filter((order) => accountId === undefined || order.accountId === accountId).map((order) => ({ ...order }));
  }

  applyProviderEvent(input: { readonly idempotencyKey: string; readonly providerOrderId: string; readonly status: "paid" | "refunded" }): PaymentOrder {
    if (!input.idempotencyKey) throw new Error("idempotencyKey is required");
    const existing = [...this.orders.values()].find((order) => order.providerOrderId === input.providerOrderId);
    if (existing === undefined) throw new Error("payment order not found");
    if (this.webhookKeys.has(input.idempotencyKey)) return { ...existing };
    const now = new Date().toISOString();
    let next: PaymentOrder;
    if (input.status === "paid") {
      if (existing.status !== "pending" && existing.status !== "paid") throw new Error(`cannot mark ${existing.status} order paid`);
      next = existing.status === "paid" ? existing : { ...existing, status: "paid", paidAt: now };
    } else {
      if (existing.status !== "paid" && existing.status !== "refunded") throw new Error(`cannot refund ${existing.status} order`);
      next = existing.status === "refunded" ? existing : { ...existing, status: "refunded", refundedAt: now };
    }
    this.webhookKeys.add(input.idempotencyKey);
    this.orders.set(existing.id, next);
    return { ...next };
  }
}

/** Development-only payment provider; production Alipay must implement signature verification. */
export class MockPaymentProvider implements PaymentProvider {
  readonly id = "mock" as const;
  async createCheckout(order: PaymentOrder): Promise<PaymentCheckout> {
    return { order: { ...order }, checkoutUrl: `/mock-pay/${encodeURIComponent(order.id)}` };
  }
  async verifyWebhook(payload: string, signature: string | undefined): Promise<{ providerOrderId: string; status: "paid" | "refunded" }> {
    if (signature !== "mock-signature") throw new Error("invalid payment signature");
    const parsed: unknown = JSON.parse(payload);
    if (parsed === null || typeof parsed !== "object") throw new Error("invalid payment payload");
    const body = parsed as Record<string, unknown>;
    if (typeof body.providerOrderId !== "string" || (body.status !== "paid" && body.status !== "refunded")) throw new Error("invalid payment event");
    return { providerOrderId: body.providerOrderId, status: body.status };
  }
}
