import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("objectKey") || !source.includes("encodeURIComponent")) throw new Error("opaque tenant file keys are missing");
if (!source.includes("validateUpload")) throw new Error("upload policy is missing");
console.log("files security checks passed");
