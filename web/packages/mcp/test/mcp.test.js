import test from "node:test";
import assert from "node:assert/strict";
import { InMemoryMcpConnectionStore, McpHttpClient, McpRegistry, McpService, namespaceTool, validateMcpUrl } from "../dist/index.js";

test("MCP registry scopes servers and namespaces tools", () => {
  const registry = new McpRegistry();
  registry.addServer({ id: "server-1", accountId: "a1", name: "Docs", url: "https://example.com/mcp", transport: "streamable_http", enabled: true, status: "connected" });
  const tools = registry.registerTools("server-1", [{ originalName: "search", description: "search", parametersJsonSchema: '{"type":"object"}', riskLevel: "safe", namespace: "mcp", enabled: true }]);
  assert.equal(tools[0].name, "mcp_server-1__search");
  assert.equal(registry.listTools("a2").length, 0);
});

test("MCP blocks local endpoints", () => { assert.throws(() => validateMcpUrl("http://127.0.0.1:8080/mcp"), /HTTPS|private/); assert.equal(namespaceTool("s", "x"), "mcp_s__x"); });

test("MCP HTTPS endpoint validation rejects credentials, query secrets and IP literals", () => {
  assert.throws(() => validateMcpUrl("https://user:pass@example.com/mcp"), /credentials/);
  assert.throws(() => validateMcpUrl("https://example.com/mcp?token=secret"), /query/);
  assert.throws(() => validateMcpUrl("https://93.184.216.34/mcp"), /private/);
  assert.throws(() => validateMcpUrl("https://service.local/mcp"), /private/);
});

test("MCP service handshakes, lists, calls the exact tool, and isolates accounts using fixtures", async () => {
  const sent = [];
  const http = async (_url, body, headers) => {
    const request = JSON.parse(body);
    sent.push({ request, headers });
    if (request.method === "initialize") return { status: 200, contentType: "application/json", sessionId: "fixture-session", body: JSON.stringify({ jsonrpc: "2.0", id: request.id, result: { protocolVersion: "2025-11-25" } }) };
    if (request.method === "notifications/initialized") return { status: 202, contentType: "", body: "" };
    if (request.method === "tools/list") return { status: 200, contentType: "application/json", sessionId: "fixture-session", body: JSON.stringify({ jsonrpc: "2.0", id: request.id, result: { tools: [{ name: "search", description: "Search records", inputSchema: { type: "object", properties: { query: { type: "string" } }, required: ["query"] } }] } }) };
    if (request.method === "tools/call") return { status: 200, contentType: "application/json", sessionId: "fixture-session", body: JSON.stringify({ jsonrpc: "2.0", id: request.id, result: { content: [{ type: "text", text: "fixture result" }] } }) };
    throw new Error("unexpected MCP method");
  };
  const service = new McpService(new InMemoryMcpConnectionStore(), new McpHttpClient(http));
  await service.create({ id: "server-a", accountId: "account-a", name: "Docs", url: "https://mcp.example/mcp", transport: "streamable_http" });
  const connected = await service.connect("account-a", "server-a");
  assert.equal(connected.tools[0].name, "mcp_server-a__search");
  assert.equal((await service.listTools("account-b")).length, 0);
  await assert.rejects(() => service.callTool("account-b", "mcp_server-a__search", { query: "exact" }), /unavailable/);
  assert.equal(await service.callTool("account-a", "mcp_server-a__search", { query: "exact" }), "fixture result");
  const toolCall = sent.find(({ request }) => request.method === "tools/call");
  assert.deepEqual(toolCall?.request.params, { name: "search", arguments: { query: "exact" } });
  assert.equal(toolCall?.headers["mcp-session-id"], "fixture-session");
  assert.deepEqual(sent.map(({ request }) => request.method), ["initialize", "notifications/initialized", "tools/list", "initialize", "notifications/initialized", "tools/call"]);
});

test("MCP client rejects redirects and mismatched JSON-RPC responses", async () => {
  const redirecting = new McpHttpClient(async () => ({ status: 302, contentType: "text/plain", body: "" }));
  await assert.rejects(() => redirecting.connect("https://mcp.example/mcp"), /302/);
  const mismatched = new McpHttpClient(async () => ({ status: 200, contentType: "application/json", body: JSON.stringify({ jsonrpc: "2.0", id: 99, result: {} }) }));
  await assert.rejects(() => mismatched.connect("https://mcp.example/mcp"), /request ID/);
});
