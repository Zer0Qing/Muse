import type { ProviderConfig } from "@muse/contracts";

export interface StoredProviderConfig extends ProviderConfig {
  readonly accountId: string;
  readonly hasSecret: boolean;
}

export interface ProviderCatalog {
  list(accountId: string): Promise<StoredProviderConfig[]>;
  get(accountId: string, providerId: string): Promise<StoredProviderConfig | undefined>;
  save(accountId: string, provider: ProviderConfig, secret: string): Promise<StoredProviderConfig>;
  resolveSecret(accountId: string, providerId: string): Promise<string | undefined>;
}

/** Development provider catalog; secrets are held in memory and never returned to callers. */
export class InMemoryProviderCatalog implements ProviderCatalog {
  private readonly configs = new Map<string, { config: StoredProviderConfig; secret: string }>();

  async list(accountId: string): Promise<StoredProviderConfig[]> { return [...this.configs.values()].filter((item) => item.config.accountId === accountId).map((item) => ({ ...item.config })); }
  async get(accountId: string, providerId: string): Promise<StoredProviderConfig | undefined> { const item = this.configs.get(`${accountId}:${providerId}`); return item?.config.accountId === accountId ? { ...item.config } : undefined; }
  async save(accountId: string, provider: ProviderConfig, secret: string): Promise<StoredProviderConfig> {
    if (!accountId || !provider.id || !secret) throw new Error("provider identity and secret are required");
    const config: StoredProviderConfig = { ...provider, accountId, hasSecret: true };
    this.configs.set(`${accountId}:${provider.id}`, { config, secret });
    return { ...config };
  }
  async resolveSecret(accountId: string, providerId: string): Promise<string | undefined> { return this.configs.get(`${accountId}:${providerId}`)?.secret; }
}
