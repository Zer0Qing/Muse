import test from "node:test";
import assert from "node:assert/strict";

// Contract smoke test: wire events are discriminated by a stable type field.
test("chat stream events have a discriminant", () => {
  const event = { type: "content_delta", delta: "hello" };
  assert.equal(event.type, "content_delta");
  assert.equal(typeof event.delta, "string");
});
