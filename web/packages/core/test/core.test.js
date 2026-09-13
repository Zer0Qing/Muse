import test from "node:test";
import assert from "node:assert/strict";

// Runtime smoke tests are kept dependency-free until the workspace test runner is added.
test("core package test harness is present", () => {
  assert.equal(typeof test, "function");
});
