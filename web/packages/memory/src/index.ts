export type MemoryImportance = 0 | 1 | 2;
export type MemorySource = "explicit" | "inferred" | "imported";

export interface MemorySpace {
  readonly id: string;
  readonly name: string;
  readonly scope: string;
  readonly createdAt: string;
}

export interface Fact {
  readonly id: string;
  readonly accountId: string;
  readonly assistantId: string;
  readonly spaceId: string;
  readonly scope: string;
  readonly content: string;
  readonly entityKey?: string;
  readonly importance: MemoryImportance;
  readonly confidence: number;
  readonly source: MemorySource;
  readonly hitCount: number;
  readonly lastHitAt?: string;
  readonly expiresAt?: string;
  readonly pinnedAt?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

function validConfidence(value: number): boolean { return Number.isFinite(value) && value >= 0 && value <= 1; }

/** Platform-neutral memory store used by API and worker adapters. */
export class MemoryStore {
  private readonly spaces = new Map<string, MemorySpace>();
  private readonly facts = new Map<string, Fact>();
  private sequence = 0;

  createSpace(accountId: string, name: string, scope = "main"): MemorySpace {
    if (!accountId || !name.trim()) throw new Error("accountId and space name are required");
    const space: MemorySpace = { id: `${accountId}:space:${++this.sequence}`, name: name.trim().slice(0, 100), scope: scope.trim() || "main", createdAt: new Date().toISOString() };
    this.spaces.set(space.id, space);
    return { ...space };
  }

  addFact(input: Omit<Fact, "id" | "hitCount" | "createdAt" | "updatedAt">): Fact {
    if (!input.accountId || !input.assistantId || !input.spaceId || !input.content.trim()) throw new Error("fact identity and content are required");
    if (!validConfidence(input.confidence)) throw new Error("confidence must be between 0 and 1");
    const now = new Date().toISOString();
    const fact: Fact = { ...input, id: `fact_${++this.sequence}`, content: input.content.trim(), hitCount: 0, createdAt: now, updatedAt: now };
    this.facts.set(fact.id, fact);
    return { ...fact };
  }

  search(accountId: string, query: string, assistantId: string, spaceId: string, limit = 20): Fact[] {
    const needle = query.trim().toLowerCase();
    if (!needle) return [];
    const safeLimit = Math.max(1, Math.min(100, Math.trunc(limit)));
    return [...this.facts.values()]
      .filter((fact) => fact.accountId === accountId && fact.assistantId === assistantId && fact.spaceId === spaceId && fact.content.toLowerCase().includes(needle))
      .filter((fact) => fact.expiresAt === undefined || fact.expiresAt > new Date().toISOString() || fact.importance === 2 || fact.pinnedAt !== undefined)
      .sort((left, right) => (right.importance - left.importance) || (right.confidence - left.confidence) || (right.hitCount - left.hitCount))
      .slice(0, safeLimit)
      .map((fact) => ({ ...fact }));
  }

  markHit(id: string): Fact {
    const fact = this.facts.get(id);
    if (!fact) throw new Error("fact not found");
    const updated: Fact = { ...fact, hitCount: fact.hitCount + 1, lastHitAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    this.facts.set(id, updated);
    return { ...updated };
  }

  decay(now = new Date()): number {
    let changed = 0;
    for (const fact of this.facts.values()) {
      if (fact.importance === 2 || fact.pinnedAt !== undefined || fact.expiresAt !== undefined) continue;
      const ageDays = (now.getTime() - new Date(fact.updatedAt).getTime()) / 86_400_000;
      if (ageDays < 30 || fact.importance === 0) continue;
      const updated: Fact = { ...fact, importance: 0, updatedAt: now.toISOString() };
      this.facts.set(fact.id, updated); changed++;
    }
    return changed;
  }
}
