import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("unsupportedAndroidTools")) throw new Error("Android capability exclusions are missing");
if (!source.includes("validateJsonSchema")) throw new Error("tool argument validation is missing");
if (/new Function|eval\s*\(/.test(source)) throw new Error("arbitrary JavaScript execution is forbidden in the tool registry");
console.log("tools security checks passed");
