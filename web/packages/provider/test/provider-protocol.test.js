import test from "node:test";
import assert from "node:assert/strict";
import { parseSseData } from "../dist/index.js";

test("provider parser handles OpenAI, Anthropic, Gemini and Responses events", () => {
  assert.deepEqual(parseSseData('{"choices":[{"delta":{"content":"hi"}}]}', "openai"), [{ type: "content_delta", delta: "hi" }]);
  assert.deepEqual(parseSseData('{"delta":{"text":"hi"}}', "anthropic"), [{ type: "content_delta", delta: "hi" }]);
  assert.deepEqual(parseSseData('{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}', "gemini"), [{ type: "content_delta", delta: "hi" }]);
  assert.deepEqual(parseSseData('{"delta":"hi"}', "openai_responses", "response.output_text.delta"), [{ type: "content_delta", delta: "hi" }]);
  assert.deepEqual(parseSseData("[DONE]", "openai"), [{ type: "done" }]);
});

test("provider parser normalizes tool calls and usage", () => {
  assert.deepEqual(parseSseData(JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, id: "call_1", function: { name: "calculator", arguments: '{"expression":' } }] } }], usage: { prompt_tokens: 4, completion_tokens: 2 } }), "openai"), [
    { type: "tool_call_delta", index: 0, id: "call_1", name: "calculator", argumentsDelta: '{"expression":' },
    { type: "usage_delta", promptTokens: 4, completionTokens: 2, reasoningTokens: 0, cachedTokens: 0 },
  ]);
  assert.deepEqual(parseSseData(JSON.stringify({ type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "call_2", name: "calculator" } }), "anthropic"), [{ type: "tool_call_delta", index: 1, id: "call_2", name: "calculator" }]);
  assert.deepEqual(parseSseData(JSON.stringify({ candidates: [{ content: { parts: [{ functionCall: { name: "calculator", args: { expression: "1+1" } } }] }, finishReason: "STOP" }], usageMetadata: { promptTokenCount: 3, candidatesTokenCount: 2 } }), "gemini"), [
    { type: "tool_call_delta", index: 0, name: "calculator", argumentsDelta: '{"expression":"1+1"}', isSnapshot: true },
    { type: "usage_delta", promptTokens: 3, completionTokens: 2, reasoningTokens: 0, cachedTokens: 0 },
    { type: "done", finishReason: "STOP" },
  ]);
});

test("malformed provider frames become typed errors", () => { assert.deepEqual(parseSseData("{bad", "openai"), [{ type: "error", message: "provider returned malformed streaming data" }]); });
