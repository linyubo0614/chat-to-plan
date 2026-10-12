// Uses the exact MCP client version shipped by Harness 0.2.0-rc.2.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import path from "node:path";
const require = createRequire(path.join(process.env.MCP_SDK_ROOT || process.cwd(), "package.json"));
const { Client } = await import(pathToFileURL(require.resolve("@modelcontextprotocol/client")));
const { StdioClientTransport } = await import(pathToFileURL(require.resolve("@modelcontextprotocol/client/stdio")));
const root = process.env.MCP_TEST_ROOT;
assert(root, "MCP_TEST_ROOT must point to an isolated synthetic backend root");
const client = new Client({ name: "planner-interop-test", version: "1.0.0" }, {
  capabilities: {}, versionNegotiation: { mode: "auto" },
});
const transport = new StdioClientTransport({ command: process.env.MCP_TEST_PYTHON || "python",
  args: ["-m", "harness.server", "--root", root], cwd: process.cwd() });
try {
  await client.connect(transport);
  const { tools } = await client.listTools();
  assert.deepEqual(tools.map(t => t.name), ["chat_planner_record", "chat_planner_status"]);
  const arguments_ = { operation_id: "mcp-sdk-test-unique-operation", text: "记下验收备忘", interpretation: {
    items: [{ kind: "memo", title: "MCP SDK 验收", category: "测试" }],
  } };
  const first = await client.callTool({ name: "chat_planner_record", arguments: arguments_ });
  assert(!first.isError, JSON.stringify(first));
  assert.equal(first.structuredContent.tasks[0].status, "written");
  const second = await client.callTool({ name: "chat_planner_record", arguments: arguments_ });
  assert.equal(second.structuredContent.duplicate, true);
  assert.equal(second.structuredContent.id, first.structuredContent.id);
  const status = await client.callTool({ name: "chat_planner_status", arguments: {} });
  assert(status.structuredContent.connections.harness);
  console.log("Harness 0.2.0-rc.2 MCP SDK: discovery, save, status and idempotent retry passed.");
} finally { await client.close(); }
