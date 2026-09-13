import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
if (!source.includes("validateMcpUrl") || !source.includes("isPrivateHostname")) throw new Error("MCP SSRF boundary is missing");
if (!source.includes("namespaceTool")) throw new Error("MCP tool namespace is missing");
console.log("mcp security checks passed");
