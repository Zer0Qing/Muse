import test from "node:test";
import assert from "node:assert/strict";
import { migrations, validateMigrationChain } from "../dist/index.js";

test("database migrations form a contiguous chain", () => {
  validateMigrationChain(migrations);
  assert.deepEqual(migrations.map((migration) => migration.version), [1, 2, 3, 4, 5, 6, 7, 8, 9]);
});
