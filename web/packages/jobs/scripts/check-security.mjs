import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("idempotencyKey") || !source.includes("maxAttempts")) throw new Error("job retry/idempotency policy missing");
console.log("jobs security checks passed");
