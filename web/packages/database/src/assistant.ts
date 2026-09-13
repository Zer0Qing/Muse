import { randomUUID } from "node:crypto";
import type { AssistantCatalog, AssistantRecord } from "@muse/core";
import type { DatabaseClient } from "./index.js";

interface AssistantRow { id: string; user_id: string; name: string; identity_prompt: string; relationship_prompt: string; style_prompt: string; provider_id: string | null; model_id: string | null; created_at: string; updated_at: string; }

function map(row: AssistantRow): AssistantRecord {
  return { id: row.id, accountId: row.user_id, name: row.name, identityPrompt: row.identity_prompt, relationshipPrompt: row.relationship_prompt, stylePrompt: row.style_prompt, ...(row.provider_id === null ? {} : { providerId: row.provider_id }), ...(row.model_id === null ? {} : { modelId: row.model_id }), createdAt: new Date(row.created_at).toISOString(), updatedAt: new Date(row.updated_at).toISOString() };
}

/** PostgreSQL Assistant repository with account ownership predicates on every operation. */
export class PostgresAssistantCatalog implements AssistantCatalog {
  constructor(private readonly db: DatabaseClient) {}
  async list(accountId: string): Promise<AssistantRecord[]> { const rows = await this.db.query<AssistantRow>("select id, user_id, name, identity_prompt, relationship_prompt, style_prompt, provider_id, model_id, created_at, updated_at from assistants where user_id = $1 order by updated_at desc", [accountId]); return rows.map(map); }
  async get(accountId: string, assistantId: string): Promise<AssistantRecord | undefined> { const rows = await this.db.query<AssistantRow>("select id, user_id, name, identity_prompt, relationship_prompt, style_prompt, provider_id, model_id, created_at, updated_at from assistants where id = $1 and user_id = $2", [assistantId, accountId]); return rows[0] ? map(rows[0]) : undefined; }
  async create(accountId: string, input: Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">): Promise<AssistantRecord> { const rows = await this.db.query<AssistantRow>("insert into assistants(id, user_id, name, identity_prompt, relationship_prompt, style_prompt) values ($1, $2, $3, $4, $5, $6) returning id, user_id, name, identity_prompt, relationship_prompt, style_prompt, provider_id, model_id, created_at, updated_at", [randomUUID(), accountId, input.name.trim().slice(0, 100), input.identityPrompt, input.relationshipPrompt, input.stylePrompt]); if (!rows[0]) throw new Error("assistant insert returned no row"); return map(rows[0]); }
  async update(accountId: string, assistant: AssistantRecord): Promise<AssistantRecord> { const rows = await this.db.query<AssistantRow>("update assistants set name = $1, identity_prompt = $2, relationship_prompt = $3, style_prompt = $4, provider_id = $5, model_id = $6, updated_at = now() where id = $7 and user_id = $8 returning id, user_id, name, identity_prompt, relationship_prompt, style_prompt, provider_id, model_id, created_at, updated_at", [assistant.name.trim().slice(0, 100), assistant.identityPrompt, assistant.relationshipPrompt, assistant.stylePrompt, assistant.providerId ?? null, assistant.modelId ?? null, assistant.id, accountId]); if (!rows[0]) throw new Error("assistant not found"); return map(rows[0]); }
}
