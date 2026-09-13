import { readFile } from "node:fs/promises";

const files = ["src/server.ts", "src/auth.ts"];
for (const file of files) {
  const source = await readFile(new URL(`../${file}`, import.meta.url), "utf8");
  if (/console\.log\([^)]*(?:password|apiKey|token|authorization)/i.test(source)) throw new Error(`${file} may log a secret`);
}
console.log("api security checks passed");
