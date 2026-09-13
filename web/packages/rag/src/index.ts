export interface TextChunk {
  readonly id: string;
  readonly accountId: string;
  readonly documentId: string;
  readonly ordinal: number;
  readonly text: string;
  readonly tokenEstimate: number;
}

export interface RagCitation {
  readonly citationId: string;
  readonly documentId: string;
  readonly chunkId: string;
  readonly title: string;
  readonly excerpt: string;
  readonly score: number;
}

export function estimateTokens(text: string): number { return Math.max(1, Math.ceil(text.trim().length / 3.5)); }

/** Deterministic paragraph-aware chunking with overlap, preserving document ownership. */
export function chunkText(accountId: string, documentId: string, text: string, maxCharacters = 1800, overlapCharacters = 180): TextChunk[] {
  if (!accountId || !documentId) throw new Error("accountId and documentId are required");
  if (!Number.isInteger(maxCharacters) || maxCharacters < 100) throw new Error("maxCharacters must be at least 100");
  if (!Number.isInteger(overlapCharacters) || overlapCharacters < 0 || overlapCharacters >= maxCharacters) throw new Error("overlapCharacters must be in range");
  const normalized = text.replace(/\r\n/g, "\n").trim();
  if (!normalized) return [];
  const paragraphs = normalized.split(/\n{2,}/).map((part) => part.trim()).filter(Boolean);
  const chunks: TextChunk[] = [];
  let current = "";
  let ordinal = 0;
  const flush = () => {
    if (!current) return;
    chunks.push({ id: `${documentId}:chunk:${ordinal}`, accountId, documentId, ordinal, text: current, tokenEstimate: estimateTokens(current) });
    ordinal++;
    current = current.slice(Math.max(0, current.length - overlapCharacters));
  };
  for (const paragraph of paragraphs) {
    if (paragraph.length <= maxCharacters) {
      if (current && current.length + paragraph.length + 2 > maxCharacters) flush();
      current = current ? `${current}\n\n${paragraph}` : paragraph;
      continue;
    }
    for (let start = 0; start < paragraph.length; start += maxCharacters - overlapCharacters) {
      const piece = paragraph.slice(start, start + maxCharacters);
      if (current) flush();
      current = piece;
      if (piece.length === maxCharacters) flush();
    }
  }
  flush();
  return chunks;
}

function terms(query: string): string[] { return query.toLowerCase().split(/\s+|[，。！？、；：]+/).map((term) => term.trim()).filter((term) => term.length > 1); }

/** Tenant-scoped lexical retrieval; vector provider can replace scoring later. */
export function searchChunks(chunks: readonly TextChunk[], query: string, accountId: string, limit = 8): TextChunk[] {
  const queryTerms = terms(query);
  if (!queryTerms.length) return [];
  return chunks.filter((chunk) => chunk.accountId === accountId).map((chunk) => {
    const lower = chunk.text.toLowerCase();
    const score = queryTerms.reduce((total, term) => total + (lower.includes(term) ? 1 : 0), 0);
    return { chunk, score };
  }).filter((item) => item.score > 0).sort((left, right) => right.score - left.score || left.chunk.ordinal - right.chunk.ordinal).slice(0, Math.max(1, Math.min(50, Math.trunc(limit)))).map((item) => item.chunk);
}

export function toCitations(chunks: readonly TextChunk[], titleByDocument: Readonly<Record<string, string>> = {}): RagCitation[] {
  return chunks.map((chunk) => ({ citationId: `cite:${chunk.id}`, documentId: chunk.documentId, chunkId: chunk.id, title: titleByDocument[chunk.documentId] ?? chunk.documentId, excerpt: chunk.text.slice(0, 360), score: 1 }));
}
