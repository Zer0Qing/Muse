import { randomUUID } from "node:crypto";
import type { DatabaseClient } from "./index.js";
import type { Fact, MemorySpace } from "@muse/memory";
import type { TextChunk } from "@muse/rag";
import type { FileDescriptor } from "@muse/files";

type McpStatus = "disabled" | "connecting" | "connected" | "failed" | "needs_auth";
interface McpServerConfig { readonly id: string; readonly accountId: string; readonly name: string; readonly url: string; readonly transport: "streamable_http"; readonly enabled: boolean; readonly status: McpStatus; }
interface McpServerInput { readonly id: string; readonly accountId: string; readonly name: string; readonly url: string; readonly transport: "streamable_http"; }
interface McpToolDefinition { readonly originalName: string; readonly description: string; readonly parametersJsonSchema: string; readonly riskLevel: "normal"; readonly namespace: "mcp"; readonly enabled: boolean; }
interface McpToolRecord extends McpToolDefinition { readonly name: string; readonly serverId: string; readonly serverName: string; }
interface McpConnectionStore {
  create(input: McpServerInput): Promise<McpServerConfig>;
  get(accountId: string, serverId: string): Promise<McpServerConfig | undefined>;
  list(accountId: string): Promise<McpServerConfig[]>;
  remove(accountId: string, serverId: string): Promise<void>;
  listTools(accountId: string): Promise<McpToolRecord[]>;
  setConnection(accountId: string, serverId: string, status: "connected" | "failed", tools: readonly Omit<McpToolDefinition, "name" | "serverId">[]): Promise<void>;
}

interface FactRow { id: string; user_id: string; assistant_id: string; space_id: string; scope: string; content: string; entity_key: string | null; importance: number; confidence: number; source: Fact["source"]; hit_count: number; last_hit_at: string | null; expires_at: string | null; pinned_at: string | null; created_at: string; updated_at: string; }
function mapFact(row: FactRow): Fact { return { id: row.id, accountId: row.user_id, assistantId: row.assistant_id, spaceId: row.space_id, scope: row.scope, content: row.content, ...(row.entity_key === null ? {} : { entityKey: row.entity_key }), importance: row.importance as Fact["importance"], confidence: Number(row.confidence), source: row.source, hitCount: row.hit_count, ...(row.last_hit_at === null ? {} : { lastHitAt: new Date(row.last_hit_at).toISOString() }), ...(row.expires_at === null ? {} : { expiresAt: new Date(row.expires_at).toISOString() }), ...(row.pinned_at === null ? {} : { pinnedAt: new Date(row.pinned_at).toISOString() }), createdAt: new Date(row.created_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString() }; }

/** Durable memory repository; every query is constrained by account, assistant and space. */
export class PostgresMemoryRepository {
  constructor(private readonly db: DatabaseClient) {}
  async createSpace(accountId: string, name: string, scope = "main"): Promise<MemorySpace> { const id = randomUUID(); const rows = await this.db.query<{ id: string; name: string; scope: string; created_at: string }>("insert into memory_spaces(id, user_id, name, scope) values ($1, $2, $3, $4) returning id, name, scope, created_at", [id, accountId, name.trim().slice(0, 100), scope]); const row = rows[0]; if (!row) throw new Error("memory space insert returned no row"); return { id: row.id, name: row.name, scope: row.scope, createdAt: new Date(row.created_at).toISOString() }; }
  async addFact(input: Omit<Fact, "id" | "hitCount" | "createdAt" | "updatedAt">): Promise<Fact> { const rows = await this.db.query<FactRow>("insert into memory_facts(id, user_id, assistant_id, space_id, scope, content, entity_key, importance, confidence, source, expires_at, pinned_at) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) returning id, user_id, assistant_id, space_id, scope, content, entity_key, importance, confidence, source, hit_count, last_hit_at, expires_at, pinned_at, created_at, updated_at", [randomUUID(), input.accountId, input.assistantId, input.spaceId, input.scope, input.content.trim(), input.entityKey ?? null, input.importance, input.confidence, input.source, input.expiresAt ?? null, input.pinnedAt ?? null]); const row = rows[0]; if (!row) throw new Error("memory fact insert returned no row"); return mapFact(row); }
  async search(accountId: string, query: string, assistantId: string, spaceId: string, limit = 20): Promise<Fact[]> { const rows = await this.db.query<FactRow>("select id, user_id, assistant_id, space_id, scope, content, entity_key, importance, confidence, source, hit_count, last_hit_at, expires_at, pinned_at, created_at, updated_at from memory_facts where user_id = $1 and assistant_id = $2 and space_id = $3 and content ilike $4 and (expires_at is null or expires_at > now() or importance = 2 or pinned_at is not null) order by importance desc, confidence desc, hit_count desc limit $5", [accountId, assistantId, spaceId, `%${query}%`, Math.max(1, Math.min(100, Math.trunc(limit)))]); return rows.map(mapFact); }
  async markHit(accountId: string, id: string): Promise<Fact> { const rows = await this.db.query<FactRow>("update memory_facts set hit_count = hit_count + 1, last_hit_at = now(), updated_at = now() where id = $1 and user_id = $2 returning id, user_id, assistant_id, space_id, scope, content, entity_key, importance, confidence, source, hit_count, last_hit_at, expires_at, pinned_at, created_at, updated_at", [id, accountId]); const row = rows[0]; if (!row) throw new Error("memory fact not found"); return mapFact(row); }
}

interface ChunkRow { id: string; user_id: string; document_id: string; ordinal: number; text: string; token_estimate: number; }
export class PostgresRagRepository {
  constructor(private readonly db: DatabaseClient) {}
  async saveChunks(chunks: readonly TextChunk[]): Promise<void> { await this.db.transaction(async (transaction) => { for (const chunk of chunks) await transaction.query("insert into rag_chunks(id, user_id, document_id, ordinal, text, token_estimate) values ($1, $2, $3, $4, $5, $6) on conflict (id) do update set text = excluded.text, token_estimate = excluded.token_estimate", [chunk.id, chunk.accountId, chunk.documentId, chunk.ordinal, chunk.text, chunk.tokenEstimate]); }); }
  async listChunks(accountId: string, documentId?: string): Promise<TextChunk[]> { const rows = documentId === undefined ? await this.db.query<ChunkRow>("select id, user_id, document_id, ordinal, text, token_estimate from rag_chunks where user_id = $1 order by document_id, ordinal", [accountId]) : await this.db.query<ChunkRow>("select id, user_id, document_id, ordinal, text, token_estimate from rag_chunks where user_id = $1 and document_id = $2 order by ordinal", [accountId, documentId]); return rows.map((row) => ({ id: row.id, accountId: row.user_id, documentId: row.document_id, ordinal: row.ordinal, text: row.text, tokenEstimate: row.token_estimate })); }
}

interface FileRow { id: string; user_id: string; object_key: string; original_name: string; mime_type: string; size_bytes: string | number; sha256: string; created_at: string; }
function mapFile(row: FileRow): FileDescriptor { return { id: row.id, accountId: row.user_id, objectKey: row.object_key, originalName: row.original_name, mimeType: row.mime_type, sizeBytes: Number(row.size_bytes), sha256: row.sha256, createdAt: new Date(row.created_at).toISOString() }; }
export class PostgresFileRepository {
  constructor(private readonly db: DatabaseClient) {}
  async create(input: Omit<FileDescriptor, "createdAt">): Promise<FileDescriptor> {
    const existing = await this.get(input.accountId, input.id);
    if (existing) {
      if (existing.objectKey === input.objectKey && existing.sha256 === input.sha256 && existing.sizeBytes === input.sizeBytes) return existing;
      throw new Error("file completion conflicts with an existing descriptor");
    }
    const rows = await this.db.query<FileRow>("insert into file_descriptors(id, user_id, object_key, original_name, mime_type, size_bytes, sha256) values ($1, $2, $3, $4, $5, $6, $7) on conflict (id) do nothing returning id, user_id, object_key, original_name, mime_type, size_bytes, sha256, created_at", [input.id, input.accountId, input.objectKey, input.originalName, input.mimeType, input.sizeBytes, input.sha256]);
    const row = rows[0];
    if (row) return mapFile(row);
    const retry = await this.get(input.accountId, input.id);
    if (retry && retry.objectKey === input.objectKey && retry.sha256 === input.sha256 && retry.sizeBytes === input.sizeBytes) return retry;
    throw new Error("file completion conflicts with an existing descriptor");
  }
  async get(accountId: string, id: string): Promise<FileDescriptor | undefined> { const rows = await this.db.query<FileRow>("select id, user_id, object_key, original_name, mime_type, size_bytes, sha256, created_at from file_descriptors where user_id = $1 and id = $2", [accountId, id]); return rows[0] ? mapFile(rows[0]) : undefined; }
}

interface McpServerRow {
  id: string;
  user_id: string;
  name: string;
  url: string;
  transport: "streamable_http";
  enabled: boolean;
  status: McpServerConfig["status"];
  tools: unknown;
  created_at: string;
  updated_at: string;
}

function mapMcpServer(row: McpServerRow): McpServerConfig {
  return { id: row.id, accountId: row.user_id, name: row.name, url: row.url, transport: row.transport, enabled: row.enabled, status: row.status };
}

/** Account-scoped MCP settings and discovered tool schemas. */
export class PostgresMcpRepository implements McpConnectionStore {
  constructor(private readonly db: DatabaseClient) {}

  async create(input: McpServerInput): Promise<McpServerConfig> {
    const rows = await this.db.query<McpServerRow>(
      "insert into mcp_servers(user_id, id, name, url, transport, enabled, status) values ($1, $2, $3, $4, $5, true, 'disabled') returning id, user_id, name, url, transport, enabled, status, tools, created_at, updated_at",
      [input.accountId, input.id, input.name, input.url, input.transport],
    );
    const row = rows[0];
    if (!row) throw new Error("MCP server insert returned no row");
    return mapMcpServer(row);
  }

  async get(accountId: string, serverId: string): Promise<McpServerConfig | undefined> {
    const rows = await this.db.query<McpServerRow>(
      "select id, user_id, name, url, transport, enabled, status, tools, created_at, updated_at from mcp_servers where user_id = $1 and id = $2",
      [accountId, serverId],
    );
    return rows[0] ? mapMcpServer(rows[0]) : undefined;
  }

  async list(accountId: string): Promise<McpServerConfig[]> {
    const rows = await this.db.query<McpServerRow>(
      "select id, user_id, name, url, transport, enabled, status, tools, created_at, updated_at from mcp_servers where user_id = $1 order by created_at desc",
      [accountId],
    );
    return rows.map(mapMcpServer);
  }

  async remove(accountId: string, serverId: string): Promise<void> {
    await this.db.query("delete from mcp_servers where user_id = $1 and id = $2", [accountId, serverId]);
  }

  async listTools(accountId: string): Promise<McpToolRecord[]> {
    const rows = await this.db.query<McpServerRow>(
      "select id, user_id, name, url, transport, enabled, status, tools, created_at, updated_at from mcp_servers where user_id = $1 and enabled = true and status = 'connected' order by created_at desc",
      [accountId],
    );
    const result: McpToolRecord[] = [];
    for (const row of rows) {
      const tools = parseStoredMcpTools(row.tools);
      for (const tool of tools) {
        result.push({ ...tool, name: `mcp_${row.id}__${tool.originalName}`, serverId: row.id, serverName: row.name });
      }
    }
    return result;
  }

  async setConnection(
    accountId: string,
    serverId: string,
    status: "connected" | "failed",
    tools: readonly Omit<McpToolDefinition, "name" | "serverId">[],
  ): Promise<void> {
    const rows = await this.db.query<McpServerRow>(
      "update mcp_servers set status = $3, tools = $4::jsonb, updated_at = now() where user_id = $1 and id = $2 returning id, user_id, name, url, transport, enabled, status, tools, created_at, updated_at",
      [accountId, serverId, status, JSON.stringify(status === "connected" ? tools : [])],
    );
    if (!rows[0]) throw new Error("MCP server not found");
  }
}

function parseStoredMcpTools(raw: unknown): Array<Omit<McpToolDefinition, "name" | "serverId">> {
  const parsed: unknown = typeof raw === "string" ? JSON.parse(raw) : raw;
  if (!Array.isArray(parsed)) return [];
  return parsed.filter((item): item is Omit<McpToolDefinition, "name" | "serverId"> =>
    item !== null && typeof item === "object" && !Array.isArray(item) &&
    typeof Reflect.get(item, "originalName") === "string" &&
    typeof Reflect.get(item, "description") === "string" &&
    typeof Reflect.get(item, "parametersJsonSchema") === "string" &&
    Reflect.get(item, "namespace") === "mcp" &&
    Reflect.get(item, "riskLevel") === "normal" &&
    Reflect.get(item, "enabled") === true,
  );
}
