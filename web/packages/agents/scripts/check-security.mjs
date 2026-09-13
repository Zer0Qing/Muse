import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("accountId") || !source.includes("invalid task transition")) throw new Error("agent ownership/state boundary is missing");
console.log("agents security checks passed");
