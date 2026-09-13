import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { type UserAccount } from "./auth.js";
import { AlipayPaymentProvider, MockPaymentProvider, reduceChatEvents } from "@muse/core";
import { DefaultProviderResolver } from "@muse/provider";
import { createApiRuntime } from "./runtime.js";
import type { ChatMessage, ChatRequest, ChatStreamEvent, ProviderConfig, PushSubscriptionRecord } from "@muse/contracts";
import { addDocumentChunked, addMcpServer, createApiDomains, createToolContext, searchDocumentChunks, validateFileUpload } from "./domains.js";

const port = Number(process.env.PORT ?? 8080);
const allowedOrigin = process.env.WEB_ORIGIN ?? "http://localhost:5173";

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "access-control-allow-origin": allowedOrigin,
    "access-control-allow-credentials": "true",
    "x-content-type-options": "nosniff",
  });
  response.end(JSON.stringify(body));
}

function requestId(request: IncomingMessage): string {
  return request.headers["x-request-id"]?.toString().slice(0, 128) ?? randomUUID();
}

async function readJsonBody(request: IncomingMessage): Promise<Record<string, unknown>> {
  let size = 0;
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > 1024 * 1024) throw new Error("request body too large");
    chunks.push(buffer);
  }
  const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("JSON object required");
  return parsed as Record<string, unknown>;
}

function stringField(body: Record<string, unknown>, name: string): string {
  const value = body[name];
  if (typeof value !== "string" || value.length === 0) throw new Error(`${name} is required`);
  return value;
}

function bearerToken(request: IncomingMessage): string | undefined {
  const header = request.headers.authorization;
  if (typeof header !== "string" || !header.startsWith("Bearer ")) return undefined;
  return header.slice("Bearer ".length).trim() || undefined;
}

const runtime = await createApiRuntime();
const { auth, billing, sessions, paymentOrders, assistants, providers, memory, rag, push, jobs, storage, files } = runtime;
const providerResolver = new DefaultProviderResolver();
const domains = createApiDomains();
process.once("SIGTERM", () => { void runtime.close(); });
process.once("SIGINT", () => { void runtime.close(); });
const adminAudit: Array<{ actorId: string; action: string; target: string; at: string }> = [];
const paymentProvider = new MockPaymentProvider();
const alipayProvider = process.env.ALIPAY_APP_ID && process.env.ALIPAY_PRIVATE_KEY_PEM && process.env.ALIPAY_PUBLIC_KEY_PEM
  ? new AlipayPaymentProvider({ appId: process.env.ALIPAY_APP_ID, privateKeyPem: process.env.ALIPAY_PRIVATE_KEY_PEM, alipayPublicKeyPem: process.env.ALIPAY_PUBLIC_KEY_PEM, gatewayUrl: process.env.ALIPAY_GATEWAY_URL ?? "https://openapi.alipay.com/gateway.do", notifyUrl: process.env.ALIPAY_NOTIFY_URL ?? "", returnUrl: process.env.ALIPAY_RETURN_URL ?? "" })
  : undefined;
const requestWindows = new Map<string, { startedAt: number; count: number }>();
const REQUEST_WINDOW_MS = 60_000;
const REQUEST_LIMIT = 30;
const bootstrapAdminEmail = process.env.BOOTSTRAP_ADMIN_EMAIL;
const bootstrapAdminPassword = process.env.BOOTSTRAP_ADMIN_PASSWORD;
if (bootstrapAdminEmail && bootstrapAdminPassword) await auth.seedAdmin(bootstrapAdminEmail, bootstrapAdminPassword);

async function currentUser(request: IncomingMessage): Promise<UserAccount | undefined> {
  return auth.authenticate(bearerToken(request));
}

function requestKey(request: IncomingMessage, userId: string): string {
  return `${userId}:${request.socket.remoteAddress ?? "unknown"}`;
}

function allowRequest(request: IncomingMessage, userId: string): boolean {
  const key = requestKey(request, userId);
  const now = Date.now();
  const current = requestWindows.get(key);
  if (current === undefined || now - current.startedAt >= REQUEST_WINDOW_MS) {
    requestWindows.set(key, { startedAt: now, count: 1 });
    return true;
  }
  if (current.count >= REQUEST_LIMIT) return false;
  current.count += 1;
  return true;
}

function providerConfigFromBody(value: unknown): ProviderConfig {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("provider is required");
  const body = value as Record<string, unknown>;
  const type = body.type;
  const allowed = ["openai", "anthropic", "gemini", "deepseek", "openai_responses", "openai_compatible", "custom"] as const;
  if (typeof type !== "string" || !allowed.includes(type as (typeof allowed)[number])) throw new Error("unsupported provider type");
  const baseUrl = body.baseUrl;
  if (typeof baseUrl !== "string" || baseUrl.length > 512) throw new Error("provider baseUrl is required");
  const parsed = new URL(baseUrl);
  const localDevelopment = process.env.NODE_ENV !== "production" && ["localhost", "127.0.0.1"].includes(parsed.hostname);
  if (parsed.protocol !== "https:" && !localDevelopment) throw new Error("provider baseUrl must use HTTPS");
  if (parsed.username || parsed.password) throw new Error("provider baseUrl must not contain credentials");
  return {
    id: typeof body.id === "string" ? body.id.slice(0, 100) : "request-provider",
    type: type as ProviderConfig["type"],
    displayName: typeof body.displayName === "string" ? body.displayName.slice(0, 100) : "Request provider",
    baseUrl: parsed.toString().replace(/\/$/, ""),
    modelIds: Array.isArray(body.modelIds) ? body.modelIds.filter((model): model is string => typeof model === "string").slice(0, 200) : [],
    supportsStreaming: true,
  };
}

function chatRequestFromBody(body: Record<string, unknown>): ChatRequest {
  const provider = providerConfigFromBody(body.provider);
  const providerId = typeof body.providerId === "string" ? body.providerId.slice(0, 100) : undefined;
  const model = stringField(body, "model").slice(0, 200);
  const mode = body.mode === "hosted" ? "hosted" : "byok";
  // The secret is intentionally not part of ChatRequest. It is extracted only at the proxy boundary.
  if (!Array.isArray(body.messages) || body.messages.length > 100) throw new Error("messages must contain 1-100 items");
  const messages = body.messages.map((item): ChatRequest["messages"][number] => {
    if (item === null || typeof item !== "object" || Array.isArray(item)) throw new Error("invalid message");
    const message = item as Record<string, unknown>;
    const role = message.role;
    if (role !== "system" && role !== "user" && role !== "assistant" && role !== "tool") throw new Error("invalid message role");
    const content = message.content;
    if (typeof content !== "string" || content.length > 128 * 1024) throw new Error("invalid message content");
    return {
      id: typeof message.id === "string" ? message.id.slice(0, 100) : randomUUID(),
      role,
      content,
      createdAt: typeof message.createdAt === "string" ? message.createdAt : new Date().toISOString(),
    };
  });
  return { provider, ...(providerId === undefined ? {} : { providerId }), model, messages, mode };
}

function assistantSystemMessage(assistant: { identityPrompt: string; relationshipPrompt: string; stylePrompt: string }): ChatMessage | undefined {
  const content = [assistant.identityPrompt, assistant.relationshipPrompt, assistant.stylePrompt].map((item) => item.trim()).filter(Boolean).join("\n\n").slice(0, 60_000);
  if (!content) return undefined;
  return { id: `system:${randomUUID()}`, role: "system", content, createdAt: new Date().toISOString() };
}

function requestSecret(request: IncomingMessage): string {
  const value = request.headers["x-muse-provider-key"];
  if (typeof value !== "string" || value.length === 0 || value.length > 4096) throw new Error("provider secret is required");
  return value;
}

function isPrivateAddress(address: string): boolean {
  if (isIP(address) === 4) {
    const octets = address.split(".").map(Number);
    const first = octets[0] ?? -1;
    const second = octets[1] ?? -1;
    return first === 10 || first === 127 || (first === 169 && second === 254) ||
      (first === 172 && second >= 16 && second <= 31) ||
      (first === 192 && second === 168) || first === 0;
  }
  const normalized = address.toLowerCase();
  return normalized === "::1" || normalized === "::" || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe8") || normalized.startsWith("fe9") || normalized.startsWith("fea") || normalized.startsWith("feb");
}

function hostedProviderConfig(): ProviderConfig {
  const type = process.env.HOSTED_PROVIDER_TYPE ?? "openai";
  const allowed = ["openai", "anthropic", "gemini", "deepseek", "openai_responses", "openai_compatible", "custom"];
  if (!allowed.includes(type)) throw new Error("hosted provider type is invalid");
  const baseUrl = process.env.HOSTED_PROVIDER_BASE_URL ?? "https://api.openai.com/v1";
  return {
    id: "hosted-default",
    type: type as ProviderConfig["type"],
    displayName: "Muse Hosted Provider",
    baseUrl,
    modelIds: (process.env.HOSTED_PROVIDER_MODELS ?? "gpt-4o-mini").split(",").map((model) => model.trim()).filter(Boolean),
    supportsStreaming: true,
  };
}

async function assertSafeProviderUrl(rawUrl: string): Promise<void> {
  const parsed = new URL(rawUrl);
  const localDevelopment = process.env.NODE_ENV !== "production" && ["localhost", "127.0.0.1"].includes(parsed.hostname);
  if (localDevelopment) return;
  const addresses = isIP(parsed.hostname) ? [parsed.hostname] : (await lookup(parsed.hostname, { all: true })).map((entry) => entry.address);
  if (addresses.length === 0 || addresses.some(isPrivateAddress)) throw new Error("provider host resolves to a private or local address");
}

function writeSseHeaders(response: ServerResponse): void {
  response.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache, no-transform",
    connection: "keep-alive",
    "access-control-allow-origin": allowedOrigin,
    "access-control-allow-credentials": "true",
    "x-content-type-options": "nosniff",
  });
}

function writeSseEvent(response: ServerResponse, event: ChatStreamEvent): void {
  response.write(`data: ${JSON.stringify(event)}\n\n`);
}

const server = createServer(async (request, response) => {
  const id = requestId(request);
  response.setHeader("x-request-id", id);
  if (request.method === "OPTIONS") {
    response.writeHead(204, {
      "access-control-allow-origin": allowedOrigin,
      "access-control-allow-credentials": "true",
      "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
      "access-control-allow-headers": "content-type, authorization, x-request-id, idempotency-key, x-muse-provider-key, x-muse-tool-approval",
    });
    response.end();
    return;
  }
  const path = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`).pathname;
  if (request.method === "GET" && path === "/health") {
    json(response, 200, { ok: true, service: "muse-api", requestId: id });
    return;
  }
  if (request.method === "GET" && path === "/api/v1/capabilities") {
    json(response, 200, {
      pwa: true,
      modes: ["byok", "hosted"],
      providers: ["openai", "anthropic", "gemini", "deepseek", "openai-compatible"],
      unsupportedAndroidCapabilities: ["accessibility", "shizuku", "root", "cross-app-control", "system-notifications"],
    });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/auth/register") {
    try {
      const body = await readJsonBody(request);
      const user = await auth.register(stringField(body, "email"), stringField(body, "password"));
      await billing.assignPlan(user.id, "free", false);
      json(response, 201, { user });
    } catch (error) {
      json(response, 400, { code: "INVALID_REQUEST", message: error instanceof Error ? error.message : "invalid request", requestId: id });
    }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/auth/login") {
    try {
      const body = await readJsonBody(request);
      const session = await auth.login(stringField(body, "email"), stringField(body, "password"));
      json(response, 200, { session });
    } catch (error) {
      json(response, 401, { code: "INVALID_CREDENTIALS", message: "invalid credentials", requestId: id });
    }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/billing/plans") {
    json(response, 200, { plans: await billing.listPlans() });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/billing/checkout") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const plan = await billing.getPlan(stringField(body, "planId"));
      if (!plan) throw new Error("plan not found");
      const price = await billing.currentPrice(plan.id);
      const provider = alipayProvider ?? paymentProvider;
      const order = await paymentOrders.create({ accountId: user.id, planId: plan.id, priceId: price.id, amountFen: body.cycle === "annual" ? price.annualPriceFen : price.monthlyPriceFen, provider: provider.id });
      json(response, 201, { checkout: await provider.createCheckout(order) });
    } catch (error) {
      json(response, 400, { code: "INVALID_CHECKOUT", message: error instanceof Error ? error.message : "invalid checkout", requestId: id });
    }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/billing/webhook/mock") {
    try {
      const body = await readJsonBody(request);
      const payload = JSON.stringify(body);
      const provider = alipayProvider ?? paymentProvider;
      const signature = provider.id === "mock" ? request.headers["x-mock-signature"]?.toString() : undefined;
      const event = await provider.verifyWebhook(payload, signature);
      const order = await paymentOrders.applyProviderEvent({ idempotencyKey: request.headers["idempotency-key"]?.toString() || randomUUID(), ...event });
      if (order.status === "paid") await billing.assignPlan(order.accountId, order.planId, true);
      json(response, 200, { order: { ...order, accountId: undefined } });
    } catch (error) {
      json(response, 400, { code: "INVALID_PAYMENT_WEBHOOK", message: error instanceof Error ? error.message : "invalid payment webhook", requestId: id });
    }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/billing/orders") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { orders: (await paymentOrders.list(user.id)).map((order) => ({ ...order, accountId: undefined })) });
    return;
  }
  if (request.method === "GET" && path === "/api/v1/billing/balance") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    const balance = await billing.balance(user.id);
    json(response, 200, { balance: { accountId: balance.accountId, balance: balance.balance } });
    return;
  }
  if (request.method === "GET" && path === "/api/v1/providers") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { providers: await providers.list(user.id) });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/providers") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const provider = providerConfigFromBody(body);
      const secret = requestSecret(request);
      await assertSafeProviderUrl(provider.baseUrl);
      json(response, 201, { provider: await providers.save(user.id, provider, secret) });
    } catch (error) { json(response, 400, { code: "INVALID_PROVIDER", message: error instanceof Error ? error.message : "invalid provider", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/push/subscriptions") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const subscription: Omit<PushSubscriptionRecord, "id" | "createdAt" | "lastSeenAt"> = { accountId: user.id, endpoint: stringField(body, "endpoint"), p256dh: stringField(body, "p256dh"), auth: stringField(body, "auth"), ...(typeof body.userAgent === "string" ? { userAgent: body.userAgent.slice(0, 500) } : {}) };
      json(response, 201, { subscription: await push.upsert(subscription) });
    } catch (error) { json(response, 400, { code: "INVALID_PUSH_SUBSCRIPTION", message: error instanceof Error ? error.message : "invalid subscription", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/push/subscriptions") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { subscriptions: await push.list(user.id) });
    return;
  }
  const assistantPath = path.match(/^\/api\/v1\/assistants\/([^/]+)$/);
  if ((request.method === "GET" || request.method === "PATCH") && assistantPath) {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    const assistantId = assistantPath[1];
    if (assistantId === undefined) { json(response, 404, { code: "ASSISTANT_NOT_FOUND", message: "assistant not found", requestId: id }); return; }
    try {
      const current = await assistants.get(user.id, decodeURIComponent(assistantId));
      if (!current) throw new Error("assistant not found");
      if (request.method === "GET") { json(response, 200, { assistant: current }); return; }
      const body = await readJsonBody(request);
      const updated = await assistants.update(user.id, { ...current, name: typeof body.name === "string" ? body.name : current.name, identityPrompt: typeof body.identityPrompt === "string" ? body.identityPrompt.slice(0, 20_000) : current.identityPrompt, relationshipPrompt: typeof body.relationshipPrompt === "string" ? body.relationshipPrompt.slice(0, 20_000) : current.relationshipPrompt, stylePrompt: typeof body.stylePrompt === "string" ? body.stylePrompt.slice(0, 20_000) : current.stylePrompt });
      json(response, 200, { assistant: updated });
    } catch (error) { json(response, 404, { code: "ASSISTANT_NOT_FOUND", message: error instanceof Error ? error.message : "assistant not found", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/assistants") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { assistants: await assistants.list(user.id) });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/assistants") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const assistant = await assistants.create(user.id, { name: stringField(body, "name"), identityPrompt: typeof body.identityPrompt === "string" ? body.identityPrompt.slice(0, 20_000) : "", relationshipPrompt: typeof body.relationshipPrompt === "string" ? body.relationshipPrompt.slice(0, 20_000) : "", stylePrompt: typeof body.stylePrompt === "string" ? body.stylePrompt.slice(0, 20_000) : "" });
      json(response, 201, { assistant });
    } catch (error) { json(response, 400, { code: "INVALID_ASSISTANT", message: error instanceof Error ? error.message : "invalid assistant", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/sessions") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { sessions: await sessions.list(user.id) });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/sessions") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const assistantId = typeof body.assistantId === "string" ? body.assistantId.slice(0, 100) : "default";
      if (assistantId !== "default" && !await assistants.get(user.id, assistantId)) throw new Error("assistant not found");
      json(response, 201, { session: await sessions.create(user.id, assistantId) });
    } catch (error) {
      json(response, 400, { code: "INVALID_SESSION", message: error instanceof Error ? error.message : "invalid session", requestId: id });
    }
    return;
  }
  const messagePath = path.match(/^\/api\/v1\/sessions\/([^/]+)\/messages$/);
  if ((request.method === "GET" || request.method === "POST") && messagePath) {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    const requestedSessionId = messagePath[1];
    if (requestedSessionId === undefined) { json(response, 404, { code: "SESSION_NOT_FOUND", message: "session not found", requestId: id }); return; }
    const sessionId = decodeURIComponent(requestedSessionId);
    try {
      if (request.method === "GET") {
        json(response, 200, { messages: await sessions.listMessages(user.id, sessionId) });
        return;
      }
      const body = await readJsonBody(request);
      const role = body.role;
      if (role !== "user" && role !== "assistant") throw new Error("only user and assistant messages can be appended");
      const content = body.content;
      if (typeof content !== "string" || content.length > 128 * 1024) throw new Error("message content is invalid");
      const message: ChatMessage = {
        id: typeof body.id === "string" && body.id.length > 0 ? body.id.slice(0, 100) : randomUUID(),
        role,
        content,
        ...(typeof body.reasoning === "string" ? { reasoning: body.reasoning.slice(0, 128 * 1024) } : {}),
        createdAt: typeof body.createdAt === "string" ? body.createdAt : new Date().toISOString(),
      };
      await sessions.appendMessage(user.id, sessionId, message);
      json(response, 201, { message });
    } catch (error) {
      const message = error instanceof Error ? error.message : "session message request failed";
      json(response, request.method === "POST" ? 400 : 404, { code: request.method === "POST" ? "INVALID_MESSAGE" : "SESSION_NOT_FOUND", message, requestId: id });
    }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/chat/stream") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    if (!allowRequest(request, user.id)) { json(response, 429, { code: "RATE_LIMITED", message: "too many requests", requestId: id }); return; }
    let hostedChargeKey: string | undefined;
    let hostedCharged = false;
    try {
      const body = await readJsonBody(request);
      const sessionId = stringField(body, "sessionId");
      const session = await sessions.get(user.id, sessionId);
      if (!session) { json(response, 404, { code: "SESSION_NOT_FOUND", message: "session not found", requestId: id }); return; }
      const requestedChat = chatRequestFromBody(body);
      const assistant = session.assistantId === "default" ? undefined : await assistants.get(user.id, session.assistantId);
      if (session.assistantId !== "default" && !assistant) throw new Error("assistant not found");
      const systemMessage = assistant ? assistantSystemMessage(assistant) : undefined;
      const contextualMessages = systemMessage === undefined
        ? requestedChat.messages.filter((message) => message.role !== "system")
        : [systemMessage, ...requestedChat.messages.filter((message) => message.role !== "system")];
      const safeTools = domains.tools.list().filter((tool) => tool.riskLevel === "safe");
      const contextualChat = { ...requestedChat, messages: contextualMessages, ...(safeTools.length === 0 ? {} : { tools: safeTools }) };
      const mode = contextualChat.mode ?? "byok";
      const plan = await billing.authorize(user.id, mode);
      const storedProvider = mode === "byok" && contextualChat.providerId
        ? await providers.get(user.id, contextualChat.providerId)
        : undefined;
      if (mode === "byok" && contextualChat.providerId && !storedProvider) throw new Error("provider configuration not found");
      const chatRequest = mode === "hosted"
        ? { ...contextualChat, provider: hostedProviderConfig(), model: stringField(body, "model") }
        : storedProvider
          ? { ...contextualChat, provider: storedProvider }
          : contextualChat;
      if (!chatRequest.provider.modelIds.length && mode === "hosted") throw new Error("hosted provider has no allowed models");
      if (mode === "hosted" && !chatRequest.provider.modelIds.includes(chatRequest.model)) throw new Error("model is not available on the hosted plan");
      if (storedProvider && storedProvider.modelIds.length > 0 && !storedProvider.modelIds.includes(chatRequest.model)) throw new Error("model is not available on this provider");
      if (mode === "byok") await assertSafeProviderUrl(chatRequest.provider.baseUrl);
      const storedSecret = storedProvider ? await providers.resolveSecret(user.id, storedProvider.id) : undefined;
      const secret = mode === "hosted" ? (process.env.HOSTED_PROVIDER_KEY ?? "") : storedSecret ?? requestSecret(request);
      if (!secret) throw new Error(mode === "hosted" ? "hosted provider is not configured" : "provider secret is required");
      const usageKey = request.headers["idempotency-key"]?.toString() || `chat:${user.id}:${randomUUID()}`;
      hostedChargeKey = usageKey;
      if (mode === "hosted") { await billing.consume(user.id, 1, "hosted_chat", usageKey); hostedCharged = true; }
      const latestUser = [...contextualChat.messages].reverse().find((message) => message.role === "user");
      if (latestUser) await sessions.appendMessage(user.id, session.id, { ...latestUser, id: latestUser.id || randomUUID() });
      const abortController = new AbortController();
      request.on("close", () => abortController.abort());
      writeSseHeaders(response);
      let loopRequest: ChatRequest = chatRequest;
      let finalResult = reduceChatEvents([]);
      for (let round = 0; round < 3; round++) {
        const roundEvents: ChatStreamEvent[] = [];
        for await (const event of providerResolver.resolve(loopRequest).stream(loopRequest, secret, abortController.signal)) {
          if (response.destroyed) break;
          roundEvents.push(event);
          if (event.type !== "done") writeSseEvent(response, event);
        }
        if (response.destroyed) break;
        const roundResult = reduceChatEvents(roundEvents);
        if (roundResult.toolCalls.length === 0) {
          for (const event of roundEvents) if (event.type === "done") writeSseEvent(response, event);
          finalResult = roundResult;
          break;
        }
        if (round === 2) throw new Error("tool call limit exceeded");
        const assistantMessage: ChatMessage = {
          id: randomUUID(), role: "assistant", content: roundResult.text,
          ...(roundResult.reasoning === null ? {} : { reasoning: roundResult.reasoning }),
          toolCalls: roundResult.toolCalls,
          createdAt: new Date().toISOString(),
        };
        loopRequest = { ...loopRequest, messages: [...loopRequest.messages, assistantMessage] };
        for (const call of roundResult.toolCalls) {
          const definition = safeTools.find((tool) => tool.name === call.name);
          if (!definition) throw new Error(`tool approval required or tool unavailable: ${call.name}`);
          let args: unknown;
          try { args = JSON.parse(call.arguments || "{}"); } catch { throw new Error(`tool arguments are invalid: ${call.name}`); }
          if (args === null || typeof args !== "object" || Array.isArray(args)) throw new Error(`tool arguments must be an object: ${call.name}`);
          const output = await domains.tools.execute(call.name, args as Record<string, unknown>, createToolContext(user.id, session.id, session.assistantId, abortController.signal));
          loopRequest = { ...loopRequest, messages: [...loopRequest.messages, { id: randomUUID(), role: "tool", content: output.slice(0, 32_000), toolCallId: call.id, toolName: call.name, createdAt: new Date().toISOString() }] };
          writeSseEvent(response, { type: "fallback_notice", message: `已执行工具：${definition.name}` });
        }
      }
      if (!response.destroyed && (finalResult.text.length > 0 || finalResult.reasoning !== null)) {
        const assistantMessage: ChatMessage = {
          id: randomUUID(), role: "assistant", content: finalResult.text,
          ...(finalResult.reasoning === null ? {} : { reasoning: finalResult.reasoning }),
          createdAt: new Date().toISOString(),
        };
        await sessions.appendMessage(user.id, session.id, assistantMessage);
      }
      if (!response.destroyed) response.end();
    } catch (error) {
      if (hostedCharged && hostedChargeKey) await billing.grant(user.id, 1, "hosted_chat_refund", `refund:${hostedChargeKey}`);
      const message = error instanceof Error ? error.message : "chat request failed";
      if (!response.headersSent) json(response, 400, { code: "CHAT_REQUEST_FAILED", message: process.env.NODE_ENV === "production" ? "chat request failed" : message, requestId: id });
      else if (!response.destroyed) { writeSseEvent(response, { type: "error", message: process.env.NODE_ENV === "production" ? "chat request failed" : message }); response.end(); }
    }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/jobs") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const types = ["memory_extract", "rag_index", "document_parse", "media_poll", "notification"] as const;
      const type = body.type;
      if (typeof type !== "string" || !types.includes(type as (typeof types)[number])) throw new Error("unsupported job type");
      const job = await jobs.enqueue({ accountId: user.id, type: type as (typeof types)[number], payload: typeof body.payload === "object" && body.payload !== null && !Array.isArray(body.payload) ? body.payload as Record<string, unknown> : {}, idempotencyKey: request.headers["idempotency-key"]?.toString() || `job:${user.id}:${randomUUID()}` });
      json(response, 202, { job });
    } catch (error) { json(response, 400, { code: "INVALID_JOB", message: error instanceof Error ? error.message : "invalid job", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/tools") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { tools: domains.tools.list() });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/tools/execute") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const args = body.args;
      if (args === null || typeof args !== "object" || Array.isArray(args)) throw new Error("args must be an object");
      const sessionId = stringField(body, "sessionId");
      if (!await sessions.get(user.id, sessionId)) throw new Error("session not found");
      const toolName = stringField(body, "name");
      const definition = domains.tools.list(false).find((tool) => tool.name === toolName);
      if (!definition) throw new Error("tool unavailable");
      if (definition.riskLevel !== "safe" && request.headers["x-muse-tool-approval"] !== "approved") throw new Error("tool approval required");
      const result = await domains.tools.execute(toolName, args as Record<string, unknown>, createToolContext(user.id, sessionId, typeof body.assistantId === "string" ? body.assistantId : "default", new AbortController().signal));
      json(response, 200, { result });
    } catch (error) {
      json(response, 400, { code: "TOOL_EXECUTION_FAILED", message: error instanceof Error ? error.message : "tool execution failed", requestId: id });
    }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/memory/facts") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const fact = await memory.addFact({ accountId: user.id, assistantId: typeof body.assistantId === "string" ? body.assistantId : "default", spaceId: stringField(body, "spaceId"), scope: typeof body.scope === "string" ? body.scope : "main", content: stringField(body, "content"), importance: body.importance === 2 ? 2 : body.importance === 1 ? 1 : 0, confidence: typeof body.confidence === "number" ? body.confidence : 1, source: body.source === "imported" ? "imported" : body.source === "inferred" ? "inferred" : "explicit" });
      json(response, 201, { fact });
    } catch (error) { json(response, 400, { code: "INVALID_FACT", message: error instanceof Error ? error.message : "invalid fact", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/memory/spaces") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try { const body = await readJsonBody(request); json(response, 201, { space: await memory.createSpace(user.id, stringField(body, "name"), typeof body.scope === "string" ? body.scope : "main") }); }
    catch (error) { json(response, 400, { code: "INVALID_MEMORY_SPACE", message: error instanceof Error ? error.message : "invalid memory space", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path.startsWith("/api/v1/memory/facts/") && path.endsWith("/hit")) {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    const factId = path.slice("/api/v1/memory/facts/".length, -"/hit".length);
    try { json(response, 200, { fact: await memory.markHit(user.id, factId) }); }
    catch (error) { json(response, 404, { code: "MEMORY_FACT_NOT_FOUND", message: error instanceof Error ? error.message : "memory fact not found", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/memory/search") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
      const hits = await memory.search(user.id, url.searchParams.get("q") ?? "", url.searchParams.get("assistantId") ?? "default", url.searchParams.get("spaceId") ?? "default");
      json(response, 200, { facts: hits });
    } catch (error) { json(response, 400, { code: "INVALID_MEMORY_QUERY", message: error instanceof Error ? error.message : "invalid memory query", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/rag/documents") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try { const body = await readJsonBody(request); const chunks = await rag.index(user.id, stringField(body, "documentId"), stringField(body, "text")); json(response, 201, { chunks }); }
    catch (error) { json(response, 400, { code: "INVALID_DOCUMENT", message: error instanceof Error ? error.message : "invalid document", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/rag/search") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    json(response, 200, await rag.search(user.id, url.searchParams.get("q") ?? ""));
    return;
  }
  if (request.method === "POST" && path === "/api/v1/files/presign") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const file = validateFileUpload({ name: stringField(body, "name"), mimeType: stringField(body, "mimeType"), sizeBytes: Number(body.sizeBytes) }, user.id, stringField(body, "fileId"));
      const result = await storage.createUploadUrl({ accountId: user.id, objectKey: file.objectKey, mimeType: stringField(body, "mimeType"), sizeBytes: Number(body.sizeBytes) });
      json(response, 201, { ...file, upload: result });
    } catch (error) { json(response, 400, { code: "INVALID_UPLOAD", message: error instanceof Error ? error.message : "invalid upload", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/files/complete") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const fileId = stringField(body, "fileId");
      const name = stringField(body, "name");
      const mimeType = stringField(body, "mimeType");
      const sizeBytes = Number(body.sizeBytes);
      const planned = validateFileUpload({ name, mimeType, sizeBytes }, user.id, fileId);
      if (body.objectKey !== planned.objectKey) throw new Error("object key does not match upload plan");
      const sha256 = stringField(body, "sha256").toLowerCase();
      if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error("sha256 must be a 64 character hex digest");
      const descriptor = await files.create({ id: fileId, accountId: user.id, objectKey: planned.objectKey, originalName: planned.downloadName, mimeType, sizeBytes, sha256 });
      json(response, 201, { file: descriptor });
    } catch (error) { json(response, 400, { code: "INVALID_FILE_COMPLETION", message: error instanceof Error ? error.message : "invalid file completion", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path.startsWith("/api/v1/files/") && path.endsWith("/download")) {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    const fileId = path.slice("/api/v1/files/".length, -"/download".length);
    try {
      const descriptor = await files.get(user.id, decodeURIComponent(fileId));
      if (!descriptor) throw new Error("file not found");
      json(response, 200, { download: await storage.createDownloadUrl({ accountId: user.id, objectKey: descriptor.objectKey, downloadName: descriptor.originalName }) });
    } catch (error) { json(response, 404, { code: "FILE_NOT_FOUND", message: error instanceof Error ? error.message : "file not found", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/files/validate") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try { const body = await readJsonBody(request); json(response, 200, validateFileUpload({ name: stringField(body, "name"), mimeType: stringField(body, "mimeType"), sizeBytes: Number(body.sizeBytes) }, user.id, stringField(body, "fileId"))); }
    catch (error) { json(response, 400, { code: "INVALID_UPLOAD", message: error instanceof Error ? error.message : "invalid upload", requestId: id }); }
    return;
  }
  if (request.method === "POST" && path === "/api/v1/mcp/servers") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try { const body = await readJsonBody(request); const config = { id: stringField(body, "id"), accountId: user.id, name: stringField(body, "name"), url: stringField(body, "url"), transport: body.transport === "sse" ? "sse" as const : "streamable_http" as const, enabled: body.enabled !== false, status: "disabled" as const }; addMcpServer(domains, config); json(response, 201, { server: config }); }
    catch (error) { json(response, 400, { code: "INVALID_MCP_SERVER", message: error instanceof Error ? error.message : "invalid MCP server", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/mcp/servers") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { servers: domains.mcp.listServers(user.id), tools: domains.mcp.listTools(user.id) });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/agents/tasks") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    try { const body = await readJsonBody(request); const task = domains.agents.create({ accountId: user.id, sessionId: stringField(body, "sessionId"), assistantId: stringField(body, "assistantId"), prompt: stringField(body, "prompt") }); json(response, 201, { task }); }
    catch (error) { json(response, 400, { code: "INVALID_AGENT_TASK", message: error instanceof Error ? error.message : "invalid agent task", requestId: id }); }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/agents/tasks") {
    const user = await currentUser(request);
    if (user === undefined) { json(response, 401, { code: "UNAUTHENTICATED", message: "authentication required", requestId: id }); return; }
    json(response, 200, { tasks: domains.agents.list(user.id) });
    return;
  }
  if (request.method === "GET" && path === "/api/v1/admin/users") {
    const user = await currentUser(request);
    if (user?.role !== "platform_admin") { json(response, 403, { code: "FORBIDDEN", message: "platform admin required", requestId: id }); return; }
    json(response, 200, { users: (await auth.listUsers()).map((item) => ({ id: item.id, email: item.email, role: item.role, createdAt: item.createdAt })) });
    return;
  }
  if (request.method === "GET" && path === "/api/v1/admin/audit") {
    const user = await currentUser(request);
    if (user?.role !== "platform_admin") { json(response, 403, { code: "FORBIDDEN", message: "platform admin required", requestId: id }); return; }
    json(response, 200, { entries: adminAudit.slice(-200) });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/admin/credits/grant") {
    const user = await currentUser(request);
    if (user?.role !== "platform_admin") { json(response, 403, { code: "FORBIDDEN", message: "platform admin required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const accountId = stringField(body, "accountId");
      const amount = Number(body.amount);
      const entry = await billing.grant(accountId, amount, stringField(body, "reason"), request.headers["idempotency-key"]?.toString() || randomUUID());
      adminAudit.push({ actorId: user.id, action: "credit_grant", target: accountId, at: new Date().toISOString() });
      json(response, 201, { entry: { ...entry, accountId: undefined } });
    } catch (error) {
      json(response, 400, { code: "INVALID_CREDIT_GRANT", message: error instanceof Error ? error.message : "invalid credit grant", requestId: id });
    }
    return;
  }
  if (request.method === "GET" && path === "/api/v1/admin/plans") {
    const user = await currentUser(request);
    if (user?.role !== "platform_admin") { json(response, 403, { code: "FORBIDDEN", message: "platform admin required", requestId: id }); return; }
    json(response, 200, { plans: await billing.listPlans(true) });
    return;
  }
  if (request.method === "POST" && path === "/api/v1/admin/plans") {
    const user = await currentUser(request);
    if (user?.role !== "platform_admin") { json(response, 403, { code: "FORBIDDEN", message: "platform admin required", requestId: id }); return; }
    try {
      const body = await readJsonBody(request);
      const plan = await billing.createPlan({
        id: stringField(body, "id"), name: stringField(body, "name"),
        monthlyPriceFen: Number(body.monthlyPriceFen), annualPriceFen: Number(body.annualPriceFen),
        includedCredits: Number(body.includedCredits), byokAllowed: body.byokAllowed === true,
        hostedModelsAllowed: body.hostedModelsAllowed === true,
        limits: typeof body.limits === "object" && body.limits !== null && !Array.isArray(body.limits) ? body.limits as Record<string, number> : {},
        createdBy: user.id,
      });
      adminAudit.push({ actorId: user.id, action: "plan_create", target: plan.id, at: new Date().toISOString() });
      json(response, 201, { plan });
    } catch (error) {
      json(response, 400, { code: "INVALID_PLAN", message: error instanceof Error ? error.message : "invalid plan", requestId: id });
    }
    return;
  }
  if (request.method === "POST" && path.startsWith("/api/v1/admin/plans/") && path.endsWith("/price")) {
    const user = await currentUser(request);
    if (user?.role !== "platform_admin") { json(response, 403, { code: "FORBIDDEN", message: "platform admin required", requestId: id }); return; }
    try {
      const planId = path.slice("/api/v1/admin/plans/".length, -"/price".length);
      const body = await readJsonBody(request);
      const plan = await billing.schedulePriceChange({
        planId, monthlyPriceFen: Number(body.monthlyPriceFen), annualPriceFen: Number(body.annualPriceFen),
        effectiveAt: stringField(body, "effectiveAt"), createdBy: user.id,
        idempotencyKey: request.headers["idempotency-key"]?.toString() || randomUUID(),
      });
      adminAudit.push({ actorId: user.id, action: "price_schedule", target: plan.id, at: new Date().toISOString() });
      json(response, 200, { plan });
    } catch (error) {
      json(response, 400, { code: "INVALID_PRICE", message: error instanceof Error ? error.message : "invalid price", requestId: id });
    }
    return;
  }
  json(response, 404, { code: "NOT_FOUND", message: "Route not found", requestId: id });
});

server.listen(port, "0.0.0.0", () => console.log(`Muse API listening on ${port}`));
