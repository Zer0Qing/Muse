import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import type { ProviderCatalog, StoredProviderConfig } from "@muse/core";
import type { ProviderConfig } from "@muse/contracts";
import type { DatabaseClient } from "./index.js";

const IV_BYTES = 12;
const KEY_BYTES = 32;

function deriveKey(master: string): Buffer { return createHash("sha256").update(master, "utf8").digest().subarray(0, KEY_BYTES); }

export class SecretVault {
  private readonly key: Buffer;
  constructor(masterKey = process.env.SECRETS_MASTER_KEY) {
    if (!masterKey || masterKey.length < 32) throw new Error("SECRETS_MASTER_KEY must contain at least 32 characters");
    this.key = deriveKey(masterKey);
  }
  encrypt(secret: string): string {
    if (!secret) throw new Error("secret is required");
    const iv = randomBytes(IV_BYTES);
    const cipher = createCipheriv("aes-256-gcm", this.key, iv);
    const ciphertext = Buffer.concat([cipher.update(secret, "utf8"), cipher.final()]);
    return [iv.toString("base64url"), cipher.getAuthTag().toString("base64url"), ciphertext.toString("base64url")].join(".");
  }
  decrypt(payload: string): string {
    const [ivText, tagText, ciphertextText] = payload.split(".");
    if (!ivText || !tagText || !ciphertextText) throw new Error("invalid secret payload");
    const decipher = createDecipheriv("aes-256-gcm", this.key, Buffer.from(ivText, "base64url"));
    decipher.setAuthTag(Buffer.from(tagText, "base64url"));
    return Buffer.concat([decipher.update(Buffer.from(ciphertextText, "base64url")), decipher.final()]).toString("utf8");
  }
}

interface ProviderRow { id: string; user_id: string; type: ProviderConfig["type"]; display_name: string; base_url: string; model_ids: string[]; secret_ciphertext: string | null; }
function mapProvider(row: ProviderRow): StoredProviderConfig { return { id: row.id, accountId: row.user_id, type: row.type, displayName: row.display_name, baseUrl: row.base_url, modelIds: row.model_ids, supportsStreaming: true, hasSecret: row.secret_ciphertext !== null }; }

/** PostgreSQL provider catalog; ciphertext is the only persisted secret representation. */
export class PostgresProviderCatalog implements ProviderCatalog {
  constructor(private readonly db: DatabaseClient, private readonly vault: SecretVault) {}
  async list(accountId: string): Promise<StoredProviderConfig[]> { const rows = await this.db.query<ProviderRow>("select id, user_id, type, display_name, base_url, model_ids, secret_ciphertext from provider_configs where user_id = $1 order by updated_at desc", [accountId]); return rows.map(mapProvider); }
  async get(accountId: string, providerId: string): Promise<StoredProviderConfig | undefined> { const rows = await this.db.query<ProviderRow>("select id, user_id, type, display_name, base_url, model_ids, secret_ciphertext from provider_configs where user_id = $1 and id = $2", [accountId, providerId]); return rows[0] ? mapProvider(rows[0]) : undefined; }
  async save(accountId: string, provider: ProviderConfig, secret: string): Promise<StoredProviderConfig> {
    const cipher = this.vault.encrypt(secret);
    const rows = await this.db.query<ProviderRow>("insert into provider_configs(id, user_id, type, display_name, base_url, model_ids, secret_ciphertext, secret_key_version) values ($1, $2, $3, $4, $5, $6, $7, 'v1') on conflict (id) do update set display_name = excluded.display_name, type = excluded.type, base_url = excluded.base_url, model_ids = excluded.model_ids, secret_ciphertext = excluded.secret_ciphertext, updated_at = now() where provider_configs.user_id = excluded.user_id returning id, user_id, type, display_name, base_url, model_ids, secret_ciphertext", [provider.id, accountId, provider.type, provider.displayName, provider.baseUrl, JSON.stringify(provider.modelIds), cipher]);
    if (!rows[0]) throw new Error("provider save returned no row");
    return mapProvider(rows[0]);
  }
  async resolveSecret(accountId: string, providerId: string): Promise<string | undefined> { const rows = await this.db.query<{ secret_ciphertext: string | null }>("select secret_ciphertext from provider_configs where user_id = $1 and id = $2", [accountId, providerId]); const cipher = rows[0]?.secret_ciphertext; return cipher ? this.vault.decrypt(cipher) : undefined; }
}
