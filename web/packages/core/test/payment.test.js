import test from "node:test";
import assert from "node:assert/strict";
import { MockPaymentProvider, PaymentOrderBook } from "../dist/index.js";

test("payment webhooks are idempotent and enforce state transitions", async () => {
  const book = new PaymentOrderBook();
  const order = book.create({ accountId: "a1", planId: "pro", priceId: "p1", amountFen: 1999, provider: "mock" });
  const paid = book.applyProviderEvent({ idempotencyKey: "hook-1", providerOrderId: order.providerOrderId, status: "paid" });
  assert.equal(paid.status, "paid");
  assert.deepEqual(book.applyProviderEvent({ idempotencyKey: "hook-1", providerOrderId: order.providerOrderId, status: "paid" }), paid);
  const refunded = book.applyProviderEvent({ idempotencyKey: "hook-2", providerOrderId: order.providerOrderId, status: "refunded" });
  assert.equal(refunded.status, "refunded");
  assert.throws(() => book.applyProviderEvent({ idempotencyKey: "hook-3", providerOrderId: order.providerOrderId, status: "paid" }), /cannot mark/);
});

test("mock payment verifies its signature", async () => {
  const provider = new MockPaymentProvider();
  await assert.rejects(provider.verifyWebhook("{}", "bad"), /invalid payment signature/);
});
