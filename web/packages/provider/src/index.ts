import type { ChatRequest, ChatStreamEvent, ProviderConfig } from "@muse/contracts";

export interface ProviderTransport {
  readonly stream: (request: ChatRequest, secret: string, signal: AbortSignal) => AsyncIterable<ChatStreamEvent>;
}

export interface ProviderResolver {
  resolve(request: ChatRequest): ProviderTransport;
}

export type ProviderAdapterType = "openai" | "anthropic" | "gemini" | "openai_responses" | "openai_compatible" | "custom";

/** Server-owned provider adapter registry; unsupported wire protocols fail closed. */
export class ProviderAdapterRegistry implements ProviderResolver {
  resolve(request: ChatRequest): ProviderTransport {
    if (["anthropic", "openai", "deepseek", "openai_responses", "openai_compatible", "custom", "gemini"].includes(request.provider.type)) return new FetchProviderTransport();
    throw new Error(`provider protocol ${request.provider.type} is not implemented`);
  }
}

function endpoint(config: ProviderConfig, model: string, secret: string): string {
  const base = config.baseUrl.replace(/\/$/, "");
  if (config.type === "anthropic") return `${base}/v1/messages`;
  if (config.type === "gemini") return `${base}/models/${encodeURIComponent(model)}:streamGenerateContent?alt=sse`;
  if (config.type === "openai_responses") return `${base}/responses`;
  return `${base}/chat/completions`;
}

function headers(request: ChatRequest, secret: string): HeadersInit {
  if (!secret) throw new Error("provider secret is required for this request");
  if (request.provider.type === "anthropic") return { "content-type": "application/json", "x-api-key": secret, "anthropic-version": "2023-06-01" };
  if (request.provider.type === "gemini") return { "content-type": "application/json", "x-goog-api-key": secret };
  return { "content-type": "application/json", authorization: `Bearer ${secret}` };
}

function openAiBody(request: ChatRequest): Record<string, unknown> {
  return {
    model: request.model,
    messages: request.messages.map((message) => ({
      role: message.role,
      content: message.content,
      ...(message.role === "assistant" && message.toolCalls ? { tool_calls: message.toolCalls.map((call) => ({ id: call.id, type: "function", function: { name: call.name, arguments: call.arguments } })) } : {}),
      ...(message.role === "tool" && message.toolCallId ? { tool_call_id: message.toolCallId } : {}),
    })), 
    stream: true,
    ...(request.temperature === undefined ? {} : { temperature: request.temperature }),
    ...(request.maxTokens === undefined ? {} : { max_tokens: request.maxTokens }),
    ...(request.tools === undefined ? {} : { tools: request.tools.map((tool) => ({ type: "function", function: { name: tool.name, description: tool.description, parameters: JSON.parse(tool.parametersJsonSchema) } })) }),
    ...(request.tools === undefined ? {} : { stream_options: { include_usage: true } }),
    ...(request.toolChoice === undefined ? {} : { tool_choice: request.toolChoice }),
  };
}

function geminiBody(request: ChatRequest): Record<string, unknown> {
  const system = request.messages.find((message) => message.role === "system")?.content;
  return {
    ...(system === undefined ? {} : { systemInstruction: { parts: [{ text: system }] } }),
    contents: request.messages.filter((message) => message.role !== "system").map((message) => ({
      role: message.role === "assistant" ? "model" : "user",
      parts: [{ text: message.content }],
    })),
    ...(request.tools === undefined ? {} : { tools: [{ functionDeclarations: request.tools.map((tool) => ({ name: tool.name, description: tool.description, parameters: JSON.parse(tool.parametersJsonSchema) })) }] }),
    ...(request.toolChoice === undefined ? {} : { toolConfig: { functionCallingConfig: { mode: request.toolChoice === "required" ? "ANY" : request.toolChoice === "none" ? "NONE" : "AUTO" } } }),
    generationConfig: {
      ...(request.temperature === undefined ? {} : { temperature: request.temperature }),
      ...(request.maxTokens === undefined ? {} : { maxOutputTokens: request.maxTokens }),
    },
  };
}

function responsesBody(request: ChatRequest): Record<string, unknown> {
  return {
    model: request.model,
    input: request.messages.map((message) => ({ role: message.role, content: message.content })),
    stream: true,
    ...(request.tools === undefined ? {} : { tools: request.tools.map((tool) => ({ type: "function", name: tool.name, description: tool.description, parameters: JSON.parse(tool.parametersJsonSchema) })) }),
    ...(request.toolChoice === undefined ? {} : { tool_choice: request.toolChoice }),
    ...(request.temperature === undefined ? {} : { temperature: request.temperature }),
    ...(request.maxTokens === undefined ? {} : { max_output_tokens: request.maxTokens }),
  };
}

function anthropicBody(request: ChatRequest): Record<string, unknown> {
  const system = request.messages.find((message) => message.role === "system")?.content;
  return {
    model: request.model,
    max_tokens: request.maxTokens ?? 4096,
    ...(system === undefined ? {} : { system }),
    messages: request.messages.filter((message) => message.role !== "system").map((message) => {
      if (message.role === "assistant" && message.toolCalls) return { role: "assistant", content: message.toolCalls.map((call) => ({ type: "tool_use", id: call.id, name: call.name, input: JSON.parse(call.arguments) })) };
      if (message.role === "tool") return { role: "user", content: [{ type: "tool_result", tool_use_id: message.toolCallId ?? "", content: message.content }] };
      return { role: message.role === "assistant" ? "assistant" : "user", content: message.content };
    }),
    ...(request.tools === undefined ? {} : { tools: request.tools.map((tool) => ({ name: tool.name, description: tool.description, input_schema: JSON.parse(tool.parametersJsonSchema) })) }),
    ...(request.toolChoice === undefined || request.toolChoice === "auto" || request.toolChoice === "none" ? {} : { tool_choice: { type: "any" } }),
    stream: true,
  };
}

function numberValue(value: unknown): number { return typeof value === "number" && Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0; }

function usageEvent(value: unknown): ChatStreamEvent | undefined {
  if (!value || typeof value !== "object") return undefined;
  const usage = value as Record<string, unknown>;
  const promptTokens = numberValue(usage.prompt_tokens ?? usage.input_tokens ?? usage.promptTokenCount);
  const completionTokens = numberValue(usage.completion_tokens ?? usage.output_tokens ?? usage.candidatesTokenCount);
  const reasoningTokens = numberValue(usage.reasoning_tokens ?? usage.thoughtsTokenCount);
  const cachedTokens = numberValue(usage.cached_tokens ?? usage.cachedContentTokenCount);
  if (promptTokens === 0 && completionTokens === 0 && reasoningTokens === 0 && cachedTokens === 0) return undefined;
  return { type: "usage_delta", promptTokens, completionTokens, reasoningTokens, cachedTokens };
}

export function parseSseData(data: string, providerType: ProviderConfig["type"], eventName = ""): ChatStreamEvent[] {
  if (data.trim() === "[DONE]") return [{ type: "done" }];
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return [{ type: "error", message: "provider returned malformed streaming data" }];
  }
  if (parsed === null || typeof parsed !== "object") return [];
  const record = parsed as Record<string, unknown>;
  if (providerType === "anthropic") {
    const result: ChatStreamEvent[] = [];
    const type = typeof record.type === "string" ? record.type : eventName;
    const delta = record.delta;
    const deltaRecord = delta && typeof delta === "object" ? delta as Record<string, unknown> : undefined;
    if (type === "content_block_start") {
      const contentBlock = record.content_block;
      if (contentBlock && typeof contentBlock === "object") {
        const block = contentBlock as Record<string, unknown>;
        if (block.type === "tool_use" && typeof block.name === "string") result.push({ type: "tool_call_delta", index: numberValue(record.index), ...(typeof block.id === "string" ? { id: block.id } : {}), name: block.name });
      }
    }
    if (type === "content_block_delta" && deltaRecord) {
      if (deltaRecord.type === "text_delta" && typeof deltaRecord.text === "string") result.push({ type: "content_delta", delta: deltaRecord.text });
      if (deltaRecord.type === "thinking_delta" && typeof deltaRecord.thinking === "string") result.push({ type: "reasoning_delta", delta: deltaRecord.thinking });
      if (deltaRecord.type === "input_json_delta" && typeof deltaRecord.partial_json === "string") result.push({ type: "tool_call_delta", index: numberValue(record.index), argumentsDelta: deltaRecord.partial_json });
    }
    if (!record.type && deltaRecord) {
      if (typeof deltaRecord.text === "string") result.push({ type: "content_delta", delta: deltaRecord.text });
      if (typeof deltaRecord.thinking === "string") result.push({ type: "reasoning_delta", delta: deltaRecord.thinking });
      if (typeof deltaRecord.stop_reason === "string") result.push({ type: "done", finishReason: deltaRecord.stop_reason });
    }
    if (type === "message_start") {
      const message = record.message;
      if (message && typeof message === "object") { const usage = usageEvent((message as Record<string, unknown>).usage); if (usage) result.push(usage); }
    }
    if (type === "message_delta" && deltaRecord) {
      if (typeof deltaRecord.stop_reason === "string") result.push({ type: "done", finishReason: deltaRecord.stop_reason });
      const usage = usageEvent(deltaRecord.usage); if (usage) result.push(usage);
    }
    if (type === "message_stop") result.push({ type: "done" });
    return result;
  }
  if (providerType === "openai_responses") {
    const result: ChatStreamEvent[] = [];
    if (eventName === "response.output_text.delta" && typeof record.delta === "string") result.push({ type: "content_delta", delta: record.delta });
    if (eventName === "response.reasoning_summary_text.delta" && typeof record.delta === "string") result.push({ type: "reasoning_delta", delta: record.delta });
    if (eventName === "response.function_call_arguments.delta" && typeof record.delta === "string") result.push({ type: "tool_call_delta", index: 0, ...(typeof record.item_id === "string" ? { id: record.item_id } : {}), argumentsDelta: record.delta });
    if (eventName === "response.output_item.added") {
      const item = record.item;
      if (item && typeof item === "object") { const value = item as Record<string, unknown>; if (value.type === "function_call") result.push({ type: "tool_call_delta", index: 0, ...(typeof value.id === "string" ? { id: value.id } : {}), ...(typeof value.name === "string" ? { name: value.name } : {}), ...(typeof value.arguments === "string" ? { argumentsDelta: value.arguments, isSnapshot: true } : {}) }); }
    }
    if (eventName === "response.completed") {
      const responseRecord = record.response;
      const usage = responseRecord && typeof responseRecord === "object" ? (responseRecord as Record<string, unknown>).usage : undefined;
      const usageResult = usageEvent(usage); if (usageResult) result.push(usageResult);
      result.push({ type: "done", finishReason: "completed" });
    }
    return result;
  }
  if (providerType === "gemini") {
    const result: ChatStreamEvent[] = [];
    const candidates = record.candidates;
    const candidate = Array.isArray(candidates) && candidates[0] && typeof candidates[0] === "object" ? candidates[0] as Record<string, unknown> : undefined;
    const content = candidate?.content;
    const parts = content && typeof content === "object" ? (content as Record<string, unknown>).parts : undefined;
    if (Array.isArray(parts)) for (const [index, value] of parts.entries()) {
      if (!value || typeof value !== "object") continue;
      const part = value as Record<string, unknown>;
      if (typeof part.text === "string") result.push({ type: "content_delta", delta: part.text });
      const call = part.functionCall;
      if (call && typeof call === "object") { const functionCall = call as Record<string, unknown>; if (typeof functionCall.name === "string") result.push({ type: "tool_call_delta", index, name: functionCall.name, ...(functionCall.args === undefined ? {} : { argumentsDelta: JSON.stringify(functionCall.args), isSnapshot: true }) }); }
    }
    const usage = usageEvent(record.usageMetadata); if (usage) result.push(usage);
    const finishReason = candidate?.finishReason;
    if (typeof finishReason === "string") result.push({ type: "done", finishReason });
    return result;
  }
  const choices = record.choices;
  const result: ChatStreamEvent[] = [];
  const usage = usageEvent(record.usage);
  if (!Array.isArray(choices) || choices.length === 0 || choices[0] === null || typeof choices[0] !== "object") { if (usage) result.push(usage); return result; }
  const choice = choices[0] as Record<string, unknown>;
  const delta = choice.delta;
  if (delta && typeof delta === "object") {
    const deltaRecord = delta as Record<string, unknown>;
    if (typeof deltaRecord.content === "string") result.push({ type: "content_delta", delta: deltaRecord.content });
    if (typeof deltaRecord.reasoning_content === "string") result.push({ type: "reasoning_delta", delta: deltaRecord.reasoning_content });
    const toolCalls = deltaRecord.tool_calls;
    if (Array.isArray(toolCalls)) for (const value of toolCalls) {
      if (!value || typeof value !== "object") continue;
      const tool = value as Record<string, unknown>;
      const functionValue = tool.function;
      const functionRecord = functionValue && typeof functionValue === "object" ? functionValue as Record<string, unknown> : undefined;
      result.push({ type: "tool_call_delta", index: numberValue(tool.index), ...(typeof tool.id === "string" ? { id: tool.id } : {}), ...(typeof functionRecord?.name === "string" ? { name: functionRecord.name } : {}), ...(typeof functionRecord?.arguments === "string" ? { argumentsDelta: functionRecord.arguments } : {}) });
    }
  }
  if (typeof choice.finish_reason === "string") result.push({ type: "done", finishReason: choice.finish_reason });
  if (usage) result.push(usage);
  return result;
}

/** Fetch-based SSE Provider transport; API keys remain in request scope only. */
export class FetchProviderTransport implements ProviderTransport {
  async *stream(request: ChatRequest, secret: string, signal: AbortSignal): AsyncIterable<ChatStreamEvent> {
    const timeout = new AbortController();
    const timeoutId = setTimeout(() => timeout.abort(), 180_000);
    const onAbort = () => timeout.abort();
    signal.addEventListener("abort", onAbort, { once: true });
    const body = request.provider.type === "anthropic" ? anthropicBody(request) : request.provider.type === "gemini" ? geminiBody(request) : request.provider.type === "openai_responses" ? responsesBody(request) : openAiBody(request);
    let response: Response;
    try {
      response = await fetch(endpoint(request.provider, request.model, secret), {
        method: "POST",
        redirect: "error",
        headers: headers(request, secret),
        body: JSON.stringify(body),
        signal: timeout.signal,
      });
    } catch (error) {
      clearTimeout(timeoutId);
      signal.removeEventListener("abort", onAbort);
      throw error;
    }
    if (!response.ok) {
      const text = (await response.text()).slice(0, 500);
      throw new Error(`provider request failed (${response.status}): ${text}`);
    }
    if (!response.body) throw new Error("provider response has no body");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const consumeFrames = (input: string): string[] => {
      const normalized = input.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
      const frames = normalized.split("\n\n");
      buffer = frames.pop() ?? "";
      return frames;
    };
    try {
      while (true) {
        const read = await reader.read();
        if (read.done) break;
        buffer += decoder.decode(read.value, { stream: true });
        if (buffer.length > 2 * 1024 * 1024) throw new Error("provider streaming response exceeded 2MB");
        for (const frame of consumeFrames(buffer)) {
          const lines = frame.split("\n");
          const eventName = lines.find((line) => line.startsWith("event:"))?.slice(6).trim() ?? "";
          const data = lines.filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n");
          if (!data) continue;
          for (const event of parseSseData(data, request.provider.type, eventName)) yield event;
        }
      }
      buffer += decoder.decode();
      if (buffer.trim()) {
        const data = buffer.split("\n").filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n");
        if (data) for (const event of parseSseData(data, request.provider.type)) yield event;
      }
    } finally {
      clearTimeout(timeoutId);
      signal.removeEventListener("abort", onAbort);
      reader.releaseLock();
    }
  }
}

export class DefaultProviderResolver implements ProviderResolver {
  resolve(request: ChatRequest): ProviderTransport { return new ProviderAdapterRegistry().resolve(request); }
}
