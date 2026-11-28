import type { ToolDefinition } from "@muse/contracts";
import type { ToolRisk } from "@muse/tools";
import { request as httpsRequest } from "node:https";
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

export type McpTransport = "sse" | "streamable_http";
export type McpStatus = "disabled" | "connecting" | "connected" | "failed" | "needs_auth";

export interface McpServerConfig {
  readonly id: string;
  readonly accountId: string;
  readonly name: string;
  readonly url: string;
  readonly transport: McpTransport;
  readonly enabled: boolean;
  readonly status: McpStatus;
}

export interface McpToolDefinition extends ToolDefinition {
  readonly serverId: string;
  readonly originalName: string;
  readonly namespace: "mcp";
  readonly enabled: boolean;
  readonly riskLevel: ToolRisk;
}

export interface McpToolRecord extends McpToolDefinition {
  readonly serverName: string;
}

export interface McpServerInput {
  readonly id: string;
  readonly accountId: string;
  readonly name: string;
  readonly url: string;
  readonly transport: "streamable_http";
}

export interface McpConnectionStore {
  create(input: McpServerInput): Promise<McpServerConfig>;
  get(accountId: string, serverId: string): Promise<McpServerConfig | undefined>;
  list(accountId: string): Promise<McpServerConfig[]>;
  remove(accountId: string, serverId: string): Promise<void>;
  listTools(accountId: string): Promise<McpToolRecord[]>;
  setConnection(accountId: string, serverId: string, status: "connected" | "failed", tools: readonly Omit<McpToolDefinition, "name" | "serverId">[]): Promise<void>;
}

export interface McpHttpResponse {
  readonly status: number;
  readonly contentType: string;
  readonly sessionId?: string;
  readonly body: string;
}

export type McpHttpRequest = (
  url: URL,
  body: string,
  headers: Readonly<Record<string, string>>,
  signal?: AbortSignal,
) => Promise<McpHttpResponse>;

function isPublicAddress(address: string): boolean {
  if (isIP(address) === 4) {
    const octets = address.split(".").map(Number);
    const a = octets[0] ?? -1;
    const b = octets[1] ?? -1;
    const c = octets[2] ?? -1;
    return !(
      a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && (b === 168 || (b === 0 && c === 0) || (b === 0 && c === 2) || (b === 88 && c === 99))) ||
      (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
      (a === 203 && b === 0 && c === 113) || a === 255
    );
  }
  if (isIP(address) === 6) {
    const normalized = address.toLowerCase();
    return normalized.startsWith("2") || normalized.startsWith("3") ? !normalized.startsWith("2001:db8:") : false;
  }
  return false;
}

function isPrivateHostname(hostname: string): boolean {
  const host = hostname.toLowerCase();
  return host === "localhost" || host === "::1" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || /^172\.(1[6-9]|2\d|3[01])\./.test(host) || host.startsWith("169.254.") || host.startsWith("fc") || host.startsWith("fd");
}

/** Validate MCP endpoints before connection; private hosts are rejected for public SaaS. */
export function validateMcpUrl(rawUrl: string, allowPrivate = false): URL {
  const url = new URL(rawUrl);
  if (url.protocol !== "https:" && !(allowPrivate && url.protocol === "http:")) throw new Error("MCP URL must use HTTPS");
  if (url.username || url.password) throw new Error("MCP URL must not contain credentials");
  if (url.search) throw new Error("MCP URL query parameters are not supported");
  if (url.hash) throw new Error("MCP URL must not contain a fragment");
  if (!allowPrivate && (isPrivateHostname(url.hostname) || isIP(url.hostname) !== 0 || /\.(local|internal|test|localhost)$/i.test(url.hostname))) throw new Error("MCP private and local hosts are not allowed");
  return url;
}

/** Send one bounded request to an already validated public HTTPS host without redirect handling. */
export const requestMcpHttps: McpHttpRequest = async (url, body, headers, signal) => {
  if (signal?.aborted) throw new Error("MCP request was cancelled");
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(new Error("MCP request timed out")), MCP_REQUEST_TIMEOUT_MS);
  const onAbort = () => controller.abort(signal?.reason);
  signal?.addEventListener("abort", onAbort, { once: true });
  const startedAt = Date.now();
  try {
    const addresses = await Promise.race([
      lookup(url.hostname, { all: true, verbatim: true }),
      new Promise<never>((_, reject) => controller.signal.addEventListener("abort", () => reject(controller.signal.reason), { once: true })),
    ]);
    if (controller.signal.aborted) throw new Error("MCP request timed out or was cancelled");
    if (addresses.length === 0 || addresses.some((entry) => !isPublicAddress(entry.address))) throw new Error("MCP host resolves to a private or non-public address");
    const pinned = addresses[0];
    if (!pinned) throw new Error("MCP host did not resolve");
    return await new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    const request = httpsRequest({
      protocol: "https:",
      hostname: url.hostname,
      port: url.port || 443,
      path: `${url.pathname}${url.search}`,
      method: "POST",
      servername: url.hostname,
      family: pinned.family,
      agent: false,
      headers: { ...headers, host: url.host, "content-length": Buffer.byteLength(body) },
      lookup: (_hostname, _options, callback) => callback(null, pinned.address, pinned.family),
      signal: controller.signal,
      timeout: Math.max(1, MCP_REQUEST_TIMEOUT_MS - (Date.now() - startedAt)),
    }, (response) => {
      response.on("data", (chunk: Buffer | string) => {
        const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        size += buffer.byteLength;
        if (size > MAX_MCP_RESPONSE_BYTES) {
          request.destroy(new Error("MCP response body exceeded the size limit"));
          return;
        }
        chunks.push(buffer);
      });
      response.on("end", () => resolve({
        status: response.statusCode ?? 0,
        contentType: response.headers["content-type"]?.toString() ?? "",
        ...(response.headers["mcp-session-id"] === undefined ? {} : { sessionId: response.headers["mcp-session-id"].toString() }),
        body: Buffer.concat(chunks).toString("utf8"),
      }));
    });
    request.on("timeout", () => request.destroy(new Error("MCP request timed out")));
    request.on("error", reject);
    request.end(body);
    });
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", onAbort);
  }
};

const MAX_MCP_RESPONSE_BYTES = 1024 * 1024;
const MCP_REQUEST_TIMEOUT_MS = 5000;
const MCP_PROTOCOL_VERSION = "2025-11-25";

interface JsonRpcResponse {
  readonly id?: unknown;
  readonly result?: Record<string, unknown>;
  readonly error?: { readonly message?: string };
}

/** Minimal stateless Streamable HTTP client; each call opens and validates its own protocol session. */
export class McpHttpClient {
  private nextRequestId = 1;

  constructor(private readonly send: McpHttpRequest = requestMcpHttps) {}

  async connect(rawUrl: string, signal?: AbortSignal): Promise<{ sessionId: string; tools: Array<Omit<McpToolDefinition, "name" | "serverId">> }> {
    const url = validateMcpUrl(rawUrl);
    const initialized = await this.rpc(url, "initialize", {
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "Muse Web", version: "1.0" },
    }, undefined, signal);
    const sessionId = initialized.sessionId ?? "";
    const acceptedVersion = initialized.result?.protocolVersion;
    if (typeof acceptedVersion !== "string" || acceptedVersion !== MCP_PROTOCOL_VERSION) throw new Error("MCP server selected an unsupported protocol version");
    await this.notifyInitialized(url, sessionId, signal);
    const listed = await this.rpc(url, "tools/list", {}, sessionId, signal);
    const rawTools = listed.result?.tools;
    if (!Array.isArray(rawTools) || rawTools.length > 100) throw new Error("MCP server returned an invalid tool list");
    const tools = rawTools.map((item): Omit<McpToolDefinition, "name" | "serverId"> => {
      if (item === null || typeof item !== "object" || Array.isArray(item)) throw new Error("MCP tool definition is invalid");
      const tool = item as Record<string, unknown>;
      if (typeof tool.name !== "string" || !/^[a-zA-Z0-9_.:-]{1,128}$/.test(tool.name)) throw new Error("MCP tool name is invalid");
      const description = typeof tool.description === "string" ? tool.description.slice(0, 2000) : "";
      const schema = tool.inputSchema;
      if (schema === null || typeof schema !== "object" || Array.isArray(schema)) throw new Error("MCP tool schema is invalid");
      const parametersJsonSchema = JSON.stringify(schema);
      if (parametersJsonSchema.length > 32_000) throw new Error("MCP tool schema is too large");
      return { originalName: tool.name, description, parametersJsonSchema, riskLevel: "normal", namespace: "mcp", enabled: true };
    });
    return { sessionId, tools };
  }

  async callTool(rawUrl: string, toolName: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<string> {
    const url = validateMcpUrl(rawUrl);
    const initialized = await this.rpc(url, "initialize", {
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "Muse Web", version: "1.0" },
    }, undefined, signal);
    const sessionId = initialized.sessionId ?? "";
    if (initialized.result?.protocolVersion !== MCP_PROTOCOL_VERSION) throw new Error("MCP server selected an unsupported protocol version");
    await this.notifyInitialized(url, sessionId, signal);
    const response = await this.rpc(url, "tools/call", { name: toolName, arguments: args }, sessionId, signal);
    if (response.result?.isError === true) throw new Error("MCP tool returned an error");
    const content = response.result?.content;
    if (!Array.isArray(content)) return JSON.stringify(response.result ?? {});
    return content.map((item) => {
      if (item && typeof item === "object" && !Array.isArray(item) && Reflect.get(item, "type") === "text" && typeof Reflect.get(item, "text") === "string") return Reflect.get(item, "text") as string;
      return JSON.stringify(item);
    }).join("\n").slice(0, MAX_MCP_RESPONSE_BYTES);
  }

  private async notifyInitialized(url: URL, sessionId: string, signal?: AbortSignal): Promise<void> {
    const response = await this.send(url, JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }), this.headers(sessionId), signal);
    if (response.status < 200 || response.status >= 300) throw new Error(`MCP initialized notification failed (${response.status})`);
  }

  private async rpc(url: URL, method: string, params: Record<string, unknown>, sessionId?: string, signal?: AbortSignal): Promise<JsonRpcResponse & McpHttpResponse> {
    const id = this.nextRequestId++;
    const response = await this.send(url, JSON.stringify({ jsonrpc: "2.0", id, method, params }), this.headers(sessionId), signal);
    if (response.status < 200 || response.status >= 300) throw new Error(`MCP request failed (${response.status})`);
    if (response.body.length > MAX_MCP_RESPONSE_BYTES) throw new Error("MCP response body exceeded the size limit");
    const payloads = response.contentType.includes("text/event-stream")
      ? response.body.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trim())
      : [response.body];
    if (payloads.length === 0) throw new Error("MCP response was empty");
    let result: JsonRpcResponse | undefined;
    for (const payload of payloads) {
      try {
        const parsed: unknown = JSON.parse(payload);
        if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed) && Reflect.get(parsed, "id") === id) {
          result = parsed as JsonRpcResponse;
          break;
        }
      } catch {
        if (!response.contentType.includes("text/event-stream")) throw new Error("MCP response was invalid");
      }
    }
    if (!result) throw new Error("MCP response did not match the request ID");
    if (result.error) throw new Error(result.error.message ?? "MCP request failed");
    return { ...result, ...response };
  }

  private headers(sessionId?: string): Record<string, string> {
    return {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
      "mcp-protocol-version": MCP_PROTOCOL_VERSION,
      ...(sessionId ? { "mcp-session-id": sessionId } : {}),
    };
  }
}

/** Namespace remote MCP tools to prevent cross-server collisions and ambiguous routing. */
export function namespaceTool(serverId: string, originalName: string): string {
  if (!/^[a-zA-Z0-9_-]{1,100}$/.test(serverId) || !/^[a-zA-Z0-9_.:-]{1,128}$/.test(originalName)) throw new Error("invalid MCP tool identity");
  return `mcp_${serverId}__${originalName}`;
}

export class McpRegistry {
  private readonly servers = new Map<string, McpServerConfig>();
  private readonly tools = new Map<string, McpToolDefinition>();

  addServer(config: McpServerConfig): void {
    validateMcpUrl(config.url);
    if (this.servers.has(config.id)) throw new Error("MCP server already exists");
    this.servers.set(config.id, { ...config });
  }

  removeServer(serverId: string): void {
    this.servers.delete(serverId);
    for (const [name, tool] of this.tools) if (tool.serverId === serverId) this.tools.delete(name);
  }

  registerTools(serverId: string, definitions: readonly Omit<McpToolDefinition, "name" | "serverId">[]): McpToolDefinition[] {
    if (!this.servers.has(serverId)) throw new Error("MCP server not found");
    const result: McpToolDefinition[] = [];
    for (const definition of definitions) {
      const tool = { ...definition, name: namespaceTool(serverId, definition.originalName), serverId };
      this.tools.set(tool.name, tool);
      result.push({ ...tool });
    }
    return result;
  }

  listServers(accountId: string): McpServerConfig[] { return [...this.servers.values()].filter((server) => server.accountId === accountId).map((server) => ({ ...server })); }
  listTools(accountId: string): McpToolDefinition[] { const ids = new Set(this.listServers(accountId).map((server) => server.id)); return [...this.tools.values()].filter((tool) => ids.has(tool.serverId)).map((tool) => ({ ...tool })); }
}

export class InMemoryMcpConnectionStore implements McpConnectionStore {
  private readonly servers = new Map<string, McpServerConfig>();
  private readonly tools = new Map<string, Omit<McpToolDefinition, "name" | "serverId">[]>();

  async create(input: McpServerInput): Promise<McpServerConfig> {
    validateMcpUrl(input.url);
    if (!input.id || input.id.length > 100 || !input.name.trim() || input.name.length > 100) throw new Error("invalid MCP server name or ID");
    if (this.servers.has(`${input.accountId}:${input.id}`)) throw new Error("MCP server already exists");
    const server: McpServerConfig = { ...input, name: input.name.trim(), enabled: true, status: "disabled" };
    this.servers.set(`${input.accountId}:${input.id}`, server);
    return { ...server };
  }

  async get(accountId: string, serverId: string): Promise<McpServerConfig | undefined> {
    const server = this.servers.get(`${accountId}:${serverId}`);
    return server ? { ...server } : undefined;
  }

  async list(accountId: string): Promise<McpServerConfig[]> {
    return [...this.servers.values()].filter((server) => server.accountId === accountId).map((server) => ({ ...server }));
  }

  async remove(accountId: string, serverId: string): Promise<void> {
    const key = `${accountId}:${serverId}`;
    this.servers.delete(key);
    this.tools.delete(key);
  }

  async listTools(accountId: string): Promise<McpToolRecord[]> {
    const result: McpToolRecord[] = [];
    for (const server of await this.list(accountId)) {
      if (!server.enabled || server.status !== "connected") continue;
      for (const definition of this.tools.get(`${accountId}:${server.id}`) ?? []) {
        result.push({ ...definition, name: namespaceTool(server.id, definition.originalName), serverId: server.id, serverName: server.name });
      }
    }
    return result;
  }

  async setConnection(accountId: string, serverId: string, status: "connected" | "failed", tools: readonly Omit<McpToolDefinition, "name" | "serverId">[]): Promise<void> {
    const key = `${accountId}:${serverId}`;
    const server = this.servers.get(key);
    if (!server) throw new Error("MCP server not found");
    this.servers.set(key, { ...server, status });
    this.tools.set(key, status === "connected" ? tools.map((tool) => ({ ...tool })) : []);
  }
}

export class McpService {
  constructor(private readonly store: McpConnectionStore, private readonly client = new McpHttpClient()) {}

  create(input: McpServerInput): Promise<McpServerConfig> {
    validateMcpUrl(input.url);
    namespaceTool(input.id, "validation");
    if (!input.name.trim() || input.name.length > 100) throw new Error("MCP server name is invalid");
    return this.store.create(input);
  }
  get(accountId: string, serverId: string): Promise<McpServerConfig | undefined> { return this.store.get(accountId, serverId); }
  listServers(accountId: string): Promise<McpServerConfig[]> { return this.store.list(accountId); }
  listTools(accountId: string): Promise<McpToolRecord[]> { return this.store.listTools(accountId); }
  async remove(accountId: string, serverId: string): Promise<void> {
    if (!await this.store.get(accountId, serverId)) throw new Error("MCP server not found");
    await this.store.remove(accountId, serverId);
  }

  async connect(accountId: string, serverId: string, signal?: AbortSignal): Promise<{ server: McpServerConfig; tools: McpToolRecord[] }> {
    const server = await this.store.get(accountId, serverId);
    if (!server) throw new Error("MCP server not found");
    try {
      const result = await this.client.connect(server.url, signal);
      await this.store.setConnection(accountId, serverId, "connected", result.tools);
      return { server: { ...server, status: "connected" }, tools: (await this.store.listTools(accountId)).filter((tool) => tool.serverId === serverId) };
    } catch (error) {
      await this.store.setConnection(accountId, serverId, "failed", []);
      throw error;
    }
  }

  async callTool(accountId: string, name: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<string> {
    const tool = (await this.store.listTools(accountId)).find((candidate) => candidate.name === name);
    if (!tool) throw new Error("MCP tool is unavailable for this account");
    const server = await this.store.get(accountId, tool.serverId);
    if (!server || !server.enabled || server.status !== "connected") throw new Error("MCP server is not connected");
    return this.client.callTool(server.url, tool.originalName, args, signal);
  }
}
