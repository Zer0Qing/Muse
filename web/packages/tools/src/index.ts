import type { ToolDefinition } from "@muse/contracts";

export type ToolRisk = "safe" | "normal" | "high";

export interface RegisteredTool extends ToolDefinition {
  readonly namespace: "builtin" | "skill" | "mcp";
  readonly enabled: boolean;
}

export interface ToolExecutionContext {
  readonly accountId: string;
  readonly sessionId: string;
  readonly assistantId: string;
  readonly signal: AbortSignal;
}

export interface ToolHandler {
  execute(args: Record<string, unknown>, context: ToolExecutionContext): Promise<string>;
}

export interface SkillDefinition {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly parametersJsonSchema: string;
  readonly implementation: "builtin" | "http" | "mcp";
  readonly enabled: boolean;
}

function isObject(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === "object" && !Array.isArray(value); }

function validateJsonSchema(args: Record<string, unknown>, schema: string): string | undefined {
  let parsed: unknown;
  try { parsed = JSON.parse(schema); } catch { return "tool schema is invalid"; }
  if (!isObject(parsed)) return "tool schema must be an object";
  const required = parsed.required;
  if (Array.isArray(required)) {
    for (const field of required) if (typeof field === "string" && !(field in args)) return `missing required argument: ${field}`;
  }
  const properties = parsed.properties;
  if (isObject(properties)) {
    for (const [name, definition] of Object.entries(properties)) {
      if (!(name in args) || !isObject(definition) || typeof definition.type !== "string") continue;
      const value = args[name];
      const valid = definition.type === "string" ? typeof value === "string" : definition.type === "number" ? typeof value === "number" && Number.isFinite(value) : definition.type === "integer" ? typeof value === "number" && Number.isSafeInteger(value) : definition.type === "boolean" ? typeof value === "boolean" : true;
      if (!valid) return `argument ${name} must be ${definition.type}`;
    }
  }
  return undefined;
}

/** Secure Web tool registry with explicit namespace, risk and schema validation. */
export class ToolRegistry {
  private readonly tools = new Map<string, { definition: RegisteredTool; handler: ToolHandler }>();

  register(definition: RegisteredTool, handler: ToolHandler): void {
    if (!/^[a-z][a-z0-9_.:-]{1,127}$/.test(definition.name)) throw new Error("invalid tool name");
    if (this.tools.has(definition.name)) throw new Error(`tool already registered: ${definition.name}`);
    this.tools.set(definition.name, { definition: { ...definition }, handler });
  }

  unregister(name: string): void { this.tools.delete(name); }
  list(enabledOnly = true): RegisteredTool[] { return [...this.tools.values()].map((item) => item.definition).filter((item) => !enabledOnly || item.enabled); }

  async execute(name: string, args: Record<string, unknown>, context: ToolExecutionContext): Promise<string> {
    const item = this.tools.get(name);
    if (!item || !item.definition.enabled) throw new Error(`tool unavailable: ${name}`);
    const validationError = validateJsonSchema(args, item.definition.parametersJsonSchema);
    if (validationError) throw new Error(validationError);
    if (context.signal.aborted) throw new Error("tool execution cancelled");
    return item.handler.execute(args, context);
  }
}

export const builtinTools: readonly RegisteredTool[] = [
  { name: "get_current_time", description: "Get the current ISO timestamp.", parametersJsonSchema: '{"type":"object","properties":{}}', riskLevel: "safe", namespace: "builtin", enabled: true },
  { name: "calculator", description: "Evaluate a bounded arithmetic expression.", parametersJsonSchema: '{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}', riskLevel: "safe", namespace: "builtin", enabled: true },
  { name: "web_search", description: "Search the public web through a configured provider.", parametersJsonSchema: '{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}', riskLevel: "safe", namespace: "builtin", enabled: true },
  { name: "read_file", description: "Read a user-uploaded file by opaque file id.", parametersJsonSchema: '{"type":"object","properties":{"fileId":{"type":"string"}},"required":["fileId"]}', riskLevel: "normal", namespace: "builtin", enabled: true },
  { name: "write_file", description: "Write a user-owned workspace file.", parametersJsonSchema: '{"type":"object","properties":{"fileId":{"type":"string"},"content":{"type":"string"}},"required":["fileId","content"]}', riskLevel: "high", namespace: "builtin", enabled: true },
];

/** Android-only operations are intentionally rejected at registration boundaries. */
export const unsupportedAndroidTools = ["screen_read", "screen_tap", "screen_input", "send_sms", "get_contacts_list", "toggle_wifi", "toggle_bluetooth", "root_shell", "shizuku"] as const;
