export interface AssistantRecord {
  readonly id: string;
  readonly accountId: string;
  readonly name: string;
  readonly identityPrompt: string;
  readonly relationshipPrompt: string;
  readonly stylePrompt: string;
  readonly providerId?: string;
  readonly modelId?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AssistantCatalog {
  list(accountId: string): Promise<AssistantRecord[]>;
  get(accountId: string, assistantId: string): Promise<AssistantRecord | undefined>;
  create(accountId: string, input: Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">): Promise<AssistantRecord>;
  update(accountId: string, assistant: AssistantRecord): Promise<AssistantRecord>;
}

/** In-memory assistant catalog used when DATABASE_URL is absent. */
export class InMemoryAssistantCatalog implements AssistantCatalog {
  private readonly assistants = new Map<string, AssistantRecord>();

  async list(accountId: string): Promise<AssistantRecord[]> { return [...this.assistants.values()].filter((assistant) => assistant.accountId === accountId).map((assistant) => ({ ...assistant })); }
  async get(accountId: string, assistantId: string): Promise<AssistantRecord | undefined> { const assistant = this.assistants.get(assistantId); return assistant?.accountId === accountId ? { ...assistant } : undefined; }
  async create(accountId: string, input: Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">): Promise<AssistantRecord> {
    if (!accountId || !input.name.trim()) throw new Error("assistant name is required");
    const now = new Date().toISOString();
    const assistant: AssistantRecord = { ...input, id: crypto.randomUUID(), accountId, name: input.name.trim().slice(0, 100), createdAt: now, updatedAt: now };
    this.assistants.set(assistant.id, assistant);
    return { ...assistant };
  }
  async update(accountId: string, assistant: AssistantRecord): Promise<AssistantRecord> {
    const current = await this.get(accountId, assistant.id);
    if (!current) throw new Error("assistant not found");
    const updated = { ...assistant, accountId, updatedAt: new Date().toISOString() };
    this.assistants.set(updated.id, updated);
    return { ...updated };
  }
}
