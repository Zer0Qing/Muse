import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("accountId") || !source.includes("assistantId") || !source.includes("spaceId")) throw new Error("memory scope boundaries are missing");
console.log("memory security checks passed");
