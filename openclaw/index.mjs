import { readFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

// OpenClaw can load ESM through require(); no top-level await in plugin entry.
const recordSchema = JSON.parse(readFileSync(new URL("./record.schema.json", import.meta.url), "utf8"));
const textResult = (details) => ({ content: [{ type: "text", text: JSON.stringify(details) }], details });

function baseUrl(config) {
  const url = new URL(config.baseUrl ?? "http://127.0.0.1:8000");
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/" ||
      !(url.protocol === "https:" || url.protocol === "http:" && ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname))) {
    throw new Error("INVALID_SERVER");
  }
  return url.origin;
}

class PlannerError extends Error {
  constructor(code, status) { super(code); this.code = code; this.status = status; }
}

export function createTools(config, context = {}) {
  const assertCurrent = () => context.assertInvocationCurrent?.();
  async function request(path, body, signal) {
    const base = baseUrl(config);
    let token;
    try { token = (await readFile(config.tokenFile, "utf8")).trim(); }
    catch { throw new PlannerError("TOKEN_FILE_UNAVAILABLE"); }
    if (token.length < 24 || /[\r\n]/.test(token)) throw new PlannerError("TOKEN_FILE_INVALID");
    assertCurrent();
    let response;
    try {
      const deadline = AbortSignal.timeout(10000);
      response = await fetch(base + path, {
        method: body === undefined ? "GET" : "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: "error", signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
      });
      if (!response.ok) throw new PlannerError("HTTP_ERROR", response.status);
      return await response.json();
    } catch (error) {
      if (error instanceof PlannerError) throw error;
      // A transport failure may follow a successful POST. The operation ID is
      // returned to the model for retry; never export raw headers/URLs/errors.
      throw new PlannerError("NETWORK_UNCONFIRMED");
    }
  }

  const fail = (error, operationId) => textResult({
    ok: false, code: error instanceof PlannerError ? error.code : "LOCAL_CONFIGURATION_ERROR",
    ...(error.status ? { http_status: error.status } : {}),
    ...(operationId ? { operation_id: operationId } : {}),
    guidance: error.status === 423 ? "功能已关闭，本次未接收任务。" : error.status === 409 ?
      "同一操作编号内容不同；不得覆盖或使用新编号盲目重试。请先查询原任务。" :
      "尚未确认结果。提交发生网络错误时可能已入队；沿用同一 operation_id 和原始内容重试，不声称已写入。",
  });
  function visible(message) {
    return {
      id: message.id, status: message.status, question: message.question,
      tasks: message.tasks.map((task) => ({
        id: task.id, title: task.payload.title, kind: task.payload.kind,
        target: task.target, status: task.status, reason: task.reason,
        starts_at: task.payload.starts_at, ends_at: task.payload.ends_at, due_at: task.payload.due_at,
        category: task.payload.category, task_date: task.payload.task_date,
        important: task.payload.important, urgent: task.payload.urgent,
      })),
      guidance: "planner 的 written 表示已保存到对话计划数据库，手机联网刷新可见；HITA 的 written 表示手机连接端报告写入并读回。",
    };
  }

  return [{
    name: "chat_planner_record", label: "记录计划",
    description: "仅在用户明确要求新增备忘、待办、日程或课程时调用。由当前模型整理，后端不再调用 DeepSeek。相对日期先查 chat_planner_status 的 server_time。不要猜测日期或结束时间，缺失填 clarification/question。待办日期 task_date 默认为今天；重要和紧急按用户意图分别设置，分类支持自定义。以返回状态确认保存或交付。失败重试沿用返回的 operation_id 和原始内容。修改或撤销已有记录不使用本工具。",
    parameters: recordSchema,
    async execute(toolCallId, params, signal) {
      const operationId = params.operation_id ?? createHash("sha256")
        .update(JSON.stringify([context.sessionId ?? context.sessionKey ?? "local", toolCallId])).digest("hex");
      try {
        if (!/^[a-f0-9]{32,64}$/.test(operationId)) throw new PlannerError("INVALID_OPERATION_ID");
        const result = await request("/v1/openclaw/plans", {
          message_id: operationId, text: params.text, interpretation: params.interpretation,
        }, signal);
        let message = result.message;
        const wait = Math.min(5000, Math.max(0, config.receiptWaitMs ?? 3000));
        const until = Date.now() + wait;
        const pending = () => message.tasks.some((t) => t.target === "hita" &&
          ["blocked", "delivering"].includes(t.status) && !t.payload.clarification &&
          !t.payload.recurrence_description && t.payload.reminder_minutes == null);
        while (pending() && Date.now() < until && !signal?.aborted) {
          await new Promise((resolve) => setTimeout(resolve, Math.min(200, until - Date.now())));
          try { message = await request(`/v1/messages/${result.id}`, undefined, signal); }
          catch { break; } // Successful submission remains confirmed even if status lookup fails.
        }
        return textResult({ ok: true, operation_id: operationId, duplicate: result.duplicate, ...visible(message) });
      } catch (error) { return fail(error, operationId); }
    },
  }, {
    name: "chat_planner_status", label: "查询计划状态",
    description: "不写入。未提供 message_id 时查询服务器时间、开关和队列；提供记录工具返回的 id 时查看真实交付状态。不要把 pending/blocked/delivering 当作成功。",
    parameters: {
      type: "object", additionalProperties: false,
      properties: { message_id: { type: "string", pattern: "^[a-f0-9]{32}$" } },
    },
    async execute(_id, params, signal) {
      try {
        if (params.message_id !== undefined && !/^[a-f0-9]{32}$/.test(params.message_id)) throw new PlannerError("INVALID_MESSAGE_ID");
        const result = await request(params.message_id ? `/v1/messages/${params.message_id}` : "/v1/status", undefined, signal);
        return textResult({ ok: true, ...(params.message_id ? visible(result) : result) });
      } catch (error) { return fail(error); }
    },
  }];
}

export default {
  id: "chat-planner", name: "对话计划",
  register(api) {
    api.registerTool((context) => {
      if (context.senderIsOwner === false) return null;
      return createTools(api.pluginConfig ?? {}, context);
    }, { names: ["chat_planner_record", "chat_planner_status"], optional: true });
  },
};
