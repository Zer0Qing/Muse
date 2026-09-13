import test from "node:test";
import assert from "node:assert/strict";
import { InMemoryJobQueue, JobWorker } from "../dist/index.js";

test("job queue deduplicates and retries with a bounded backoff", async () => {
  const queue = new InMemoryJobQueue();
  const first = await queue.enqueue({ accountId: "a1", type: "rag_index", payload: { documentId: "d1" }, idempotencyKey: "index-d1" });
  assert.equal((await queue.enqueue({ accountId: "a1", type: "rag_index", payload: {}, idempotencyKey: "index-d1" })).id, first.id);
  const worker = new JobWorker(queue, { rag_index: async () => { throw new Error("temporary"); } });
  await worker.runOnce("w1");
  assert.equal(await queue.claim("w2", Date.now()), undefined);
});
