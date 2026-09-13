import test from "node:test";
import assert from "node:assert/strict";
import { ToolRegistry, builtinTools, unsupportedAndroidTools } from "../dist/index.js";

test("tool registry validates schema and cancellation", async () => {
  const registry = new ToolRegistry();
  registry.register(builtinTools[1], { execute: async (args) => String(args.expression) });
  const context = { accountId: "a", sessionId: "s", assistantId: "as", signal: new AbortController().signal };
  assert.equal(await registry.execute("calculator", { expression: "1+1" }, context), "1+1");
  await assert.rejects(registry.execute("calculator", {}, context), /missing required/);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(registry.execute("calculator", { expression: "1" }, { ...context, signal: controller.signal }), /cancelled/);
});

test("Android-only tools are explicit exclusions", () => { assert.ok(unsupportedAndroidTools.includes("screen_read")); });
