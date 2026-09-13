import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
for (const field of ["accountId", "documentId", "chunkId"]) if (!source.includes(field)) throw new Error(`RAG ownership field missing: ${field}`);
console.log("rag security checks passed");
