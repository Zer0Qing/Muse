/** Wire-level chat events shared by the browser and all server-side consumers. */
export type ChatStreamEvent =
  | { type: "content_delta"; delta: string }
  | { type: "reasoning_delta"; delta: string; signature?: string; encryptedContent?: string }
  | { type: "image_delta"; imageBase64: string; mimeType: string }
  | { type: "tool_call_delta"; index: number; id?: string; name?: string; argumentsDelta?: string; isSnapshot?: boolean }
  | { type: "usage_delta"; promptTokens: number; completionTokens: number; reasoningTokens: number; cachedTokens: number }
  | { type: "citation_delta"; urls: string[] }
  | { type: "fallback_notice"; message: string }
  | { type: "done"; finishReason?: string }
  | { type: "stream_interrupted"; message: string }
  | { type: "error"; message: string };

export type ChatRole = "system" | "user" | "assistant" | "tool";

export interface ChatMessage {
  readonly id: string;
  readonly role: ChatRole;
  readonly content: string;
  readonly reasoning?: string;
  readonly toolCallId?: string;
  readonly toolName?: string;
  readonly imageUrls?: readonly string[];
  readonly toolCalls?: readonly { readonly id: string; readonly name: string; readonly arguments: string }[];
  readonly createdAt: string;
}

export interface ProviderConfig {
  readonly id: string;
  readonly type: "openai" | "anthropic" | "gemini" | "deepseek" | "openai_responses" | "openai_compatible" | "custom";
  readonly displayName: string;
  readonly baseUrl: string;
  readonly modelIds: readonly string[];
  readonly supportsStreaming: boolean;
}

export interface ChatRequest {
  readonly messages: readonly ChatMessage[];
  readonly provider: ProviderConfig;
  /** Optional account-owned provider configuration used by the server proxy. */
  readonly providerId?: string;
  readonly model: string;
  /** Provider secrets are resolved outside the domain request and never travel in this object. */
  readonly secretRef?: string;
  readonly mode?: "byok" | "hosted";
  /** BYOK may use direct browser transport or the optional server proxy. */
  readonly transport?: "direct" | "proxy";
  readonly temperature?: number;
  readonly maxTokens?: number;
  readonly tools?: readonly ToolDefinition[];
  readonly toolChoice?: "auto" | "required" | "none";
  readonly nativeWebSearch?: boolean;
}

export interface ToolDefinition {
  readonly name: string;
  readonly description: string;
  readonly parametersJsonSchema: string;
  readonly riskLevel: "safe" | "normal" | "high";
}

export interface PlanDefinition {
  readonly id: string;
  readonly name: string;
  readonly currency: "CNY";
  readonly monthlyPriceFen: number;
  readonly includedCredits: number;
  readonly byokAllowed: boolean;
  readonly hostedModelsAllowed: boolean;
  readonly limits: Readonly<Record<string, number>>;
}

export * from "./notifications.js";

export interface ApiErrorBody {
  readonly code: string;
  readonly message: string;
  readonly requestId?: string;
}
