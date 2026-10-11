import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import plugin, { createTools } from "../openclaw/index.mjs";

const token = "only-a-fake-test-token-for-local-server";
const messageId = "c".repeat(32);
const item = { kind: "course", title: "高数", starts_at: "2026-10-12T15:00:00+08:00", ends_at: "2026-10-12T16:00:00+08:00" };
const params = { text: "明天15点到16点高数", interpretation: { items: [item] } };
function message(status = "blocked") {
  return { id: messageId, status: "parsed", question: null, tasks: [{ id: "task", target: "hita", status, reason: "waiting", payload: item }] };
}

async function fixture(t, handler, config = {}) {
  const dir = await mkdtemp(join(tmpdir(), "planner-plugin-"));
  const tokenFile = join(dir, "token");
  await writeFile(tokenFile, token);
  const server = createServer(async (req, res) => {
    assert.equal(req.headers.authorization, `Bearer ${token}`);
    let body = "";
    for await (const chunk of req) body += chunk;
    res.setHeader("Content-Type", "application/json");
    await handler(req, res, body ? JSON.parse(body) : undefined);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise((r) => server.close(r)); await rm(dir, { recursive: true }); });
  return createTools({ baseUrl: `http://127.0.0.1:${server.address().port}`, tokenFile, receiptWaitMs: 0, ...config }, { sessionId: "session" });
}

test("actual HTTP submission and retry keep one operation identifier", async (t) => {
  const operations = [];
  const tools = await fixture(t, (req, res, body) => {
    assert.equal(req.url, "/v1/openclaw/plans");
    assert.equal(body.text, params.text);
    assert.deepEqual(body.interpretation, params.interpretation);
    operations.push(body.message_id);
    res.end(JSON.stringify({ id: messageId, message: message(), duplicate: operations.length > 1 }));
  });
  const first = (await tools[0].execute("call-one", params)).details;
  const retry = (await tools[0].execute("different-call", { ...params, operation_id: first.operation_id })).details;
  assert.equal(first.ok, true);
  assert.equal(first.tasks[0].status, "blocked");
  assert.equal(retry.duplicate, true);
  assert.equal(operations[0], operations[1]);
});

test("uncertain transport failure returns reusable ID without exposing credentials", async (t) => {
  let savedId;
  const tools = await fixture(t, (req, res, body) => { savedId = body.message_id; req.socket.destroy(); });
  const result = await tools[0].execute("call-uncertain", params);
  assert.equal(result.details.ok, false);
  assert.equal(result.details.code, "NETWORK_UNCONFIRMED");
  assert.equal(result.details.operation_id, savedId);
  assert.equal(JSON.stringify(result).includes(token), false);
});

test("pause and conflict are reported without blind resubmission", async (t) => {
  let calls = 0;
  const tools = await fixture(t, (_req, res) => { res.statusCode = ++calls === 1 ? 423 : 409; res.end("{}"); });
  assert.equal((await tools[0].execute("one", params)).details.http_status, 423);
  assert.equal((await tools[0].execute("two", params)).details.http_status, 409);
  assert.equal(calls, 2);
});

test("bounded status check reports the subsequent HITA receipt", async (t) => {
  let reads = 0;
  const tools = await fixture(t, (req, res) => {
    if (req.method === "POST") res.end(JSON.stringify({ id: messageId, message: message(), duplicate: false }));
    else { reads++; res.end(JSON.stringify(message("written"))); }
  }, { receiptWaitMs: 1000 });
  const result = await tools[0].execute("receipt", params);
  assert.equal(result.details.tasks[0].status, "written");
  assert.equal(reads, 1);
});

test("failure during status lookup retains confirmed submission", async (t) => {
  const tools = await fixture(t, (req, res) => {
    if (req.method === "POST") res.end(JSON.stringify({ id: messageId, message: message(), duplicate: false }));
    else { res.statusCode = 503; res.end("{}"); }
  }, { receiptWaitMs: 1000 });
  const result = await tools[0].execute("receipt-offline", params);
  assert.equal(result.details.ok, true);
  assert.equal(result.details.tasks[0].status, "blocked");
});

test("status is read-only and preserves server time", async (t) => {
  const tools = await fixture(t, (req, res) => {
    assert.equal(req.method, "GET");
    assert.equal(req.url, "/v1/status");
    res.end(JSON.stringify({ server_time: "2026-10-11T18:00:00+08:00", enabled: true }));
  });
  assert.equal((await tools[1].execute("status", {})).details.server_time, "2026-10-11T18:00:00+08:00");
});

test("insecure/credential-bearing servers and traversal arguments fail before requests", async () => {
  for (const baseUrl of ["http://example.com", "https://user:pass@example.com", "https://example.com/?token=secret"]) {
    const tools = createTools({ baseUrl, tokenFile: "/missing" });
    assert.equal((await tools[0].execute("bad", params)).details.ok, false);
  }
  const tools = createTools({ tokenFile: "/missing" });
  assert.equal((await tools[1].execute("path", { message_id: "../../settings" })).details.code, "INVALID_MESSAGE_ID");
  assert.equal((await tools[0].execute("bad-id", { ...params, operation_id: "../" })).details.code, "INVALID_OPERATION_ID");
});

test("host authority and abort stop effect while host-authorized agents receive tools", async (t) => {
  let factory;
  plugin.register({ pluginConfig: {}, registerTool: (f) => { factory = f; } });
  assert.equal(factory({ senderIsOwner: false }).length, 2);
  const tools = await fixture(t, (_req, res) => { res.statusCode = 500; res.end("{}"); });
  const controller = new AbortController(); controller.abort();
  assert.equal((await tools[0].execute("aborted", params, controller.signal)).details.ok, false);
});
