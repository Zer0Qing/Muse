import test from "node:test";
import assert from "node:assert/strict";
import { BillingCatalog } from "../dist/index.js";

test("price changes are versioned and preserve the old price", () => {
  const catalog = new BillingCatalog();
  const before = catalog.getPlan("pro");
  assert.ok(before);
  const after = catalog.schedulePriceChange({
    planId: "pro",
    monthlyPriceFen: 2999,
    annualPriceFen: 29990,
    effectiveAt: "2030-01-01T00:00:00.000Z",
    createdBy: "admin",
    idempotencyKey: "pro-price-2030",
  });
  assert.equal(after.prices.length, before.prices.length + 1);
  assert.equal(after.prices[0].monthlyPriceFen, 1999);
  assert.equal(after.prices.at(-1).monthlyPriceFen, 2999);
});

test("credit consumption is idempotent and prevents overdraft", () => {
  const catalog = new BillingCatalog();
  catalog.grant("account-1", 10, "plan grant", "grant-1");
  const first = catalog.consume("account-1", 3, "chat", "usage-1");
  const repeated = catalog.consume("account-1", 3, "chat", "usage-1");
  assert.deepEqual(repeated, first);
  assert.equal(catalog.balance("account-1").balance, 7);
  assert.throws(() => catalog.consume("account-1", 8, "chat", "usage-2"), /insufficient credits/);
});
