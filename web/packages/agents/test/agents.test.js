import test from "node:test";
import assert from "node:assert/strict";
import { AgentTaskStore, canRunGroupChat, isTerminal } from "../dist/index.js";

test("agent task transitions are explicit and tenant scoped", () => {
  const store = new AgentTaskStore();
  const task = store.create({ accountId: "a1", sessionId: "s1", assistantId: "as1", prompt: "research" });
  assert.equal(store.transition("a1", task.id, "running").status, "running");
  assert.throws(() => store.transition("a1", task.id, "queued"), /invalid task transition/);
  assert.equal(store.get("a2", task.id), undefined);
  assert.equal(isTerminal(store.transition("a1", task.id, "cancelled").status), true);
});

test("group chat validation prevents duplicate or oversized membership", () => { assert.equal(canRunGroupChat("round_robin", ["a", "b"]), true); assert.equal(canRunGroupChat("free_rotation", ["a", "a"]), false); });
