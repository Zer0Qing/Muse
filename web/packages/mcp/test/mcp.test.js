import test from "node:test";
import assert from "node:assert/strict";
import { McpRegistry, namespaceTool, validateMcpUrl } from "../dist/index.js";

test("MCP registry scopes servers and namespaces tools", () => {
  const registry = new McpRegistry();
  registry.addServer({ id: "server-1", accountId: "a1", name: "Docs", url: "https://example.com/mcp", transport: "streamable_http", enabled: true, status: "connected" });
  const tools = registry.registerTools("server-1", [{ originalName: "search", description: "search", parametersJsonSchema: '{"type":"object"}', riskLevel: "safe", namespace: "mcp", enabled: true }]);
  assert.equal(tools[0].name, "mcp_server-1__search");
  assert.equal(registry.listTools("a2").length, 0);
});

test("MCP blocks local endpoints", () => { assert.throws(() => validateMcpUrl("http://127.0.0.1:8080/mcp"), /HTTPS|private/); assert.equal(namespaceTool("s", "x"), "mcp_s__x"); });
