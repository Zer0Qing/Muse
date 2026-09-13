import test from "node:test";
import assert from "node:assert/strict";
import { FetchProviderTransport } from "../dist/index.js";

const request = {
  provider: { id: "p", type: "openai", displayName: "OpenAI", baseUrl: "https://example.test/v1", modelIds: ["m"], supportsStreaming: true },
  model: "m",
  mode: "byok",
  messages: [{ id: "u", role: "user", content: "hello", createdAt: new Date().toISOString() }],
};

test("transport sends provider auth and parses CRLF SSE plus usage-only frame", async () => {
  const originalFetch = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, init) => {
    captured = { url, init };
    const body = [
      `data: ${JSON.stringify({ choices: [{ delta: { content: "hi" } }] })}\r\n\r\n`,
      `data: ${JSON.stringify({ choices: [], usage: { prompt_tokens: 3, completion_tokens: 2 } })}\r\n\r\n`,
      "data: [DONE]\r\n\r\n",
    ].join("");
    return new Response(new Blob([body]), { status: 200, headers: { "content-type": "text/event-stream" } });
  };
  try {
    const events = [];
    for await (const event of new FetchProviderTransport().stream(request, "sk-test", new AbortController().signal)) events.push(event);
    assert.equal(captured.url, "https://example.test/v1/chat/completions");
    assert.equal(new Headers(captured.init.headers).get("authorization"), "Bearer sk-test");
    assert.deepEqual(JSON.parse(captured.init.body), { model: "m", messages: [{ role: "user", content: "hello" }], stream: true });
    assert.deepEqual(events, [
      { type: "content_delta", delta: "hi" },
      { type: "usage_delta", promptTokens: 3, completionTokens: 2, reasoningTokens: 0, cachedTokens: 0 },
      { type: "done" },
    ]);
  } finally { globalThis.fetch = originalFetch; }
});

test("transport emits Anthropic tool and usage events", async () => {
  const originalFetch = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, init) => {
    captured = { url, init };
    const body = [
      `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 5 } } })}\n\n`,
      `event: content_block_start\ndata: ${JSON.stringify({ type: "content_block_start", index: 0, content_block: { type: "tool_use", id: "call", name: "calculator" } })}\n\n`,
      `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", index: 0, delta: { type: "input_json_delta", partial_json: "{}" } })}\n\n`,
      `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 4 } })}\n\n`,
      `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`,
    ].join("");
    return new Response(new Blob([body]), { status: 200, headers: { "content-type": "text/event-stream" } });
  };
  try {
    const events = [];
    for await (const event of new FetchProviderTransport().stream({ ...request, provider: { ...request.provider, type: "anthropic", baseUrl: "https://anthropic.test" } }, "sk-ant", new AbortController().signal)) events.push(event);
    assert.equal(captured.url, "https://anthropic.test/v1/messages");
    assert.equal(new Headers(captured.init.headers).get("x-api-key"), "sk-ant");
    assert.deepEqual(events, [
      { type: "usage_delta", promptTokens: 5, completionTokens: 0, reasoningTokens: 0, cachedTokens: 0 },
      { type: "tool_call_delta", index: 0, id: "call", name: "calculator" },
      { type: "tool_call_delta", index: 0, argumentsDelta: "{}" },
      { type: "done", finishReason: "tool_use" },
      { type: "done" },
    ]);
  } finally { globalThis.fetch = originalFetch; }
});
