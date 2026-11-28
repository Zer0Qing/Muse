import test from "node:test";
import assert from "node:assert/strict";
import { migrations, PostgresMcpRepository, validateMigrationChain } from "../dist/index.js";

test("database migrations form a contiguous chain", () => {
  validateMigrationChain(migrations);
  assert.deepEqual(migrations.map((migration) => migration.version), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test("MCP server settings persist across repository instances and remain account-scoped", async () => {
  const rows = new Map();
  const db = {
    async query(sql, parameters = []) {
      if (sql.startsWith("insert into mcp_servers")) {
        const [user_id, id, name, url, transport] = parameters;
        const row = { id, user_id, name, url, transport, enabled: true, status: "disabled", tools: [], created_at: new Date().toISOString(), updated_at: new Date().toISOString() };
        rows.set(`${user_id}:${id}`, row);
        return [row];
      }
      if (sql.startsWith("update mcp_servers")) {
        const [user_id, id, status, tools] = parameters;
        const row = rows.get(`${user_id}:${id}`);
        if (!row) return [];
        Object.assign(row, { status, tools: JSON.parse(tools) });
        return [row];
      }
      if (sql.includes("from mcp_servers where user_id = $1 and enabled = true")) {
        return [...rows.values()].filter((row) => row.user_id === parameters[0] && row.enabled && row.status === "connected");
      }
      if (sql.includes("from mcp_servers where user_id = $1 and id = $2")) {
        const row = rows.get(`${parameters[0]}:${parameters[1]}`);
        return row ? [row] : [];
      }
      if (sql.includes("from mcp_servers where user_id = $1 order by")) {
        return [...rows.values()].filter((row) => row.user_id === parameters[0]);
      }
      if (sql.startsWith("delete from mcp_servers")) { rows.delete(`${parameters[0]}:${parameters[1]}`); return []; }
      throw new Error(`unexpected query: ${sql}`);
    },
    async transaction(work) { return work(this); },
  };
  const first = new PostgresMcpRepository(db);
  await first.create({ id: "mcp-one", accountId: "account-one", name: "Docs", url: "https://mcp.example/mcp", transport: "streamable_http" });
  await first.setConnection("account-one", "mcp-one", "connected", [{ originalName: "search", description: "Search", parametersJsonSchema: '{"type":"object"}', riskLevel: "normal", namespace: "mcp", enabled: true }]);
  const restarted = new PostgresMcpRepository(db);
  assert.equal((await restarted.list("account-one"))[0].status, "connected");
  assert.equal((await restarted.listTools("account-one"))[0].name, "mcp_mcp-one__search");
  assert.equal((await restarted.list("account-two")).length, 0);
  assert.equal((await restarted.listTools("account-two")).length, 0);
});
