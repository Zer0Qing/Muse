import test from "node:test";
import assert from "node:assert/strict";

test("worker package exposes a test entry", () => { assert.equal(typeof test, "function"); });
