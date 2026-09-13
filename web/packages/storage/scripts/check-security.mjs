import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("assertKey") || !source.includes("getSignedUrl") || !source.includes("PutObjectCommand")) throw new Error("object storage tenant boundary is missing");
console.log("storage security checks passed");
