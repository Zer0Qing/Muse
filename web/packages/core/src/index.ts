import type { ChatMessage, ChatStreamEvent } from "@muse/contracts";
export * from "./billing.js";
export * from "./chat.js";
export * from "./payment.js";
export * from "./assistant.js";
export * from "./provider-config.js";

export interface NormalizedChatResult {
  readonly text: string;
  readonly reasoning: string | null;
  readonly toolCalls: readonly { id: string; name: string; arguments: string }[];
  readonly finishReason: string | null;
  readonly usage: { promptTokens: number; completionTokens: number; reasoningTokens: number; cachedTokens: number } | null;
  readonly citations: readonly string[];
  readonly images: readonly string[];
  readonly error: string | null;
  readonly interrupted: boolean;
}

interface ToolAccumulator {
  id: string | undefined;
  name: string | undefined;
  args: string;
}

/** Aggregate streaming and non-streaming events into one deterministic result. */
export function reduceChatEvents(events: Iterable<ChatStreamEvent>): NormalizedChatResult {
  let text = "";
  let reasoning = "";
  let finishReason: string | null = null;
  let usage: NormalizedChatResult["usage"] = null;
  let error: string | null = null;
  let interrupted = false;
  let doneSeen = false;
  const images: string[] = [];
  const citations = new Set<string>();
  const tools = new Map<number, ToolAccumulator>();

  for (const event of events) {
    const incremental = event.type === "content_delta" || event.type === "reasoning_delta" || event.type === "image_delta" || event.type === "tool_call_delta";
    if (doneSeen && incremental) continue;
    switch (event.type) {
      case "content_delta":
        text += event.delta;
        break;
      case "reasoning_delta":
        reasoning += event.delta;
        break;
      case "image_delta":
        images.push(event.imageBase64);
        break;
      case "tool_call_delta": {
        const current = tools.get(event.index) ?? { id: undefined, name: undefined, args: "" };
        if (current.id === undefined && event.id !== undefined) current.id = event.id;
        if (current.name === undefined && event.name !== undefined) current.name = event.name;
        if (event.argumentsDelta !== undefined) current.args = event.isSnapshot === true ? event.argumentsDelta : current.args + event.argumentsDelta;
        tools.set(event.index, current);
        break;
      }
      case "usage_delta":
        usage = event;
        break;
      case "citation_delta":
        for (const url of event.urls) citations.add(url);
        break;
      case "done":
        finishReason = event.finishReason ?? null;
        doneSeen = true;
        break;
      case "stream_interrupted":
        error = event.message;
        interrupted = true;
        break;
      case "error":
        error = event.message;
        break;
      case "fallback_notice":
        break;
    }
  }

  return {
    text,
    reasoning: reasoning.length > 0 ? reasoning : null,
    toolCalls: [...tools.entries()].sort(([a], [b]) => a - b).map(([index, tool]) => ({ id: tool.id ?? `call_index_${index}`, name: tool.name ?? "", arguments: tool.args })),
    finishReason,
    usage,
    citations: [...citations],
    images,
    error,
    interrupted,
  };
}

/** Convert a complete response into the same event protocol used by SSE. */
export function completionToEvents(completion: {
  readonly text: string;
  readonly reasoning?: string;
  readonly toolCalls?: readonly { id: string; name: string; arguments: string }[];
  readonly finishReason?: string;
}): ChatStreamEvent[] {
  const events: ChatStreamEvent[] = [];
  if (completion.reasoning) events.push({ type: "reasoning_delta", delta: completion.reasoning });
  if (completion.text) events.push({ type: "content_delta", delta: completion.text });
  for (const [index, tool] of (completion.toolCalls ?? []).entries()) {
    events.push({ type: "tool_call_delta", index, id: tool.id, name: tool.name, argumentsDelta: tool.arguments, isSnapshot: true });
  }
  if (completion.finishReason === undefined) events.push({ type: "done" });
  else events.push({ type: "done", finishReason: completion.finishReason });
  return events;
}

/** Enforce a server-side output ceiling before persisting or returning model text. */
export function clampText(text: string, maxCharacters: number): string {
  if (!Number.isInteger(maxCharacters) || maxCharacters < 1) throw new Error("maxCharacters must be a positive integer");
  return text.length <= maxCharacters ? text : `${text.slice(0, maxCharacters)}\n[output truncated]`;
}

export function latestUserMessage(messages: readonly ChatMessage[]): ChatMessage | undefined {
  return [...messages].reverse().find((message) => message.role === "user");
}
