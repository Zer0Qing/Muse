import type { ToolDefinition } from "@muse/contracts";
import type { ToolRisk } from "@muse/tools";

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
  readonly riskLevel: ToolRisk;
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
  if (!allowPrivate && isPrivateHostname(url.hostname)) throw new Error("MCP private and local hosts are not allowed");
  return url;
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
