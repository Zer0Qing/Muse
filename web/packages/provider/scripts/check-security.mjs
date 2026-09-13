import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (/console\.(log|error).*apiKey/i.test(source)) throw new Error("provider must not log api keys");
if (!source.includes("secret: string")) throw new Error("provider secret boundary is missing");
console.log("provider security checks passed");
