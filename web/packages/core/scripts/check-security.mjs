import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (/console\.(log|error)\s*\(/.test(source)) throw new Error("Core package must not log user/provider content directly");
console.log("core security checks passed");
