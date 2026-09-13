import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (/api[_-]?key\s*:\s*string/i.test(source)) {
  throw new Error("Provider contracts must not expose raw API key fields");
}
console.log("contracts security checks passed");
