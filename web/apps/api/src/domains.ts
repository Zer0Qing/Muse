import { AgentTaskStore } from "@muse/agents";
import { defaultUploadPolicy, objectKey, sanitizeDownloadName, validateUpload } from "@muse/files";
import { McpRegistry, validateMcpUrl, type McpServerConfig } from "@muse/mcp";
import { MemoryStore } from "@muse/memory";
import { chunkText, searchChunks, toCitations, type TextChunk } from "@muse/rag";
import { builtinTools, ToolRegistry, type ToolExecutionContext } from "@muse/tools";

export interface ApiDomains {
  readonly memory: MemoryStore;
  readonly mcp: McpRegistry;
  readonly agents: AgentTaskStore;
  readonly tools: ToolRegistry;
  readonly chunks: TextChunk[];
}

function calculator(expression: string): string {
  if (!/^[0-9+\-*/().%\s]+$/.test(expression) || expression.length > 200) throw new Error("only bounded arithmetic expressions are allowed");
  let cursor = 0;
  const skip = () => { while (cursor < expression.length && /\s/.test(expression[cursor] ?? "")) cursor++; };
  const primary = (): number => {
    skip();
    if (expression[cursor] === "(") { cursor++; const value = additive(); skip(); if (expression[cursor] !== ")") throw new Error("unbalanced expression"); cursor++; return value; }
    const start = cursor;
    while (cursor < expression.length && /[0-9.]/.test(expression[cursor] ?? "")) cursor++;
    const value = Number(expression.slice(start, cursor));
    if (start === cursor || !Number.isFinite(value)) throw new Error("invalid number");
    return value;
  };
  const unary = (): number => { skip(); if (expression[cursor] === "+") { cursor++; return unary(); } if (expression[cursor] === "-") { cursor++; return -unary(); } return primary(); };
  const multiplicative = (): number => { let value = unary(); while (true) { skip(); const operator = expression[cursor]; if (!["*", "/", "%"].includes(operator ?? "")) return value; cursor++; const right = unary(); if (operator === "*") value *= right; else if (operator === "/") value /= right; else value %= right; if (!Number.isFinite(value)) throw new Error("expression did not produce a finite number"); } };
  function additive(): number { let value = multiplicative(); while (true) { skip(); const operator = expression[cursor]; if (operator !== "+" && operator !== "-") return value; cursor++; const right = multiplicative(); value = operator === "+" ? value + right : value - right; if (!Number.isFinite(value)) throw new Error("expression did not produce a finite number"); } }
  const value = additive();
  skip();
  if (cursor !== expression.length) throw new Error("unexpected arithmetic token");
  return String(value);
}

export function createApiDomains(): ApiDomains {
  const tools = new ToolRegistry();
  const timeTool = builtinTools.find((tool) => tool.name === "get_current_time");
  const calculatorTool = builtinTools.find((tool) => tool.name === "calculator");
  if (!timeTool || !calculatorTool) throw new Error("required builtin tools are missing");
  tools.register(timeTool, { execute: async () => new Date().toISOString() });
  tools.register(calculatorTool, { execute: async (args) => calculator(String(args.expression)) });
  return { memory: new MemoryStore(), mcp: new McpRegistry(), agents: new AgentTaskStore(), tools, chunks: [] };
}

export function addDocumentChunked(domains: ApiDomains, accountId: string, documentId: string, text: string): TextChunk[] {
  const chunks = chunkText(accountId, documentId, text);
  domains.chunks.push(...chunks);
  return chunks;
}

export function searchDocumentChunks(domains: ApiDomains, accountId: string, query: string, limit = 8) {
  const hits = searchChunks(domains.chunks, query, accountId, limit);
  return { chunks: hits, citations: toCitations(hits) };
}

export function validateFileUpload(input: { name: string; mimeType: string; sizeBytes: number }, accountId: string, fileId: string) {
  validateUpload(input, defaultUploadPolicy);
  return { objectKey: objectKey(accountId, fileId, input.name), downloadName: sanitizeDownloadName(input.name) };
}

export function addMcpServer(domains: ApiDomains, config: McpServerConfig): void { validateMcpUrl(config.url); domains.mcp.addServer(config); }

export function createToolContext(accountId: string, sessionId: string, assistantId: string, signal: AbortSignal): ToolExecutionContext {
  return { accountId, sessionId, assistantId, signal };
}
