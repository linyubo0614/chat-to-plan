"""Small MCP stdio server; stdout is exclusively newline-delimited JSON-RPC.

The local backend owns validation and transactions. No model or IM credentials
are needed here. Supports the legacy MCP versions accepted by Harness's bridge.
"""
import argparse
import json
from pathlib import Path
import re
import sys

import httpx
from pydantic import ValidationError

from backend.models import OpenClawPlan
from scripts.generate_openclaw_schema import schema

INSTRUCTIONS = (
    "用户要求记下具体信息、待办、课程或日程时调用 chat_planner_record；纯聊天不记录。"
    "相对日期先通过 chat_planner_status 查服务器时间，使用返回时区。"
    "缺少课程日期或结束时间时追问，不猜测。每次新记录使用新的 operation_id，"
    "失败重试保持同一编号及内容，网络中断不代表保存失败。"
    "planner 的 written 表示数据库已保存；HITA 的 written 才表示手机已写入并读回。"
    "blocked/delivering 只能说明等待手机，不能声称已写入 HITA。"
)


def tools():
    record = schema()
    # Require a model-chosen id before sending, so a timeout or host restart can
    # retry the very same operation even when the tool result never arrived.
    record["properties"]["operation_id"] = {
        "type": "string", "pattern": "^[a-zA-Z0-9_-]{16,128}$",
        "description": "每次新操作生成唯一编号（例如 UUID）；重试必须沿用同一编号和内容。",
    }
    record["required"].append("operation_id")
    return [{"name": "chat_planner_record", "description": INSTRUCTIONS,
             "inputSchema": record,
             "annotations": {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True}},
            {"name": "chat_planner_status", "description": "查询服务器时间、处理开关、队列；传 message_id 查询实际保存和 HITA 交付状态。",
             "inputSchema": {"type": "object", "additionalProperties": False,
                             "properties": {"message_id": {"type": "string", "pattern": "^[a-f0-9]{32}$"}}},
             "annotations": {"readOnlyHint": True}}]


def result(data, error=False):
    return {"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}],
            "structuredContent": data, "isError": error}


def visible(message):
    return {"id": message["id"], "status": message["status"], "question": message.get("question"),
            "tasks": [{"id": t["id"], "title": t["payload"]["title"], "target": t["target"],
                       "status": t["status"], "reason": t.get("reason")}
                      for t in message["tasks"]]}


class PlannerTools:
    def __init__(self, root, client=None):
        self.root = Path(root)
        # Only local service; never forward the management token to a user URL
        # or a system HTTP proxy. WSL users must run this process on Windows.
        self.client = client or httpx.Client(base_url="http://127.0.0.1:8000", timeout=10,
                                            follow_redirects=False, trust_env=False)

    def request(self, path, body=None):
        token = (self.root / "data/api-token").read_text(encoding="utf-8").strip()
        if len(token) < 24 or "\n" in token or "\r" in token:
            raise ValueError("Invalid local credential")
        response = self.client.request("GET" if body is None else "POST", path,
                                       headers={"Authorization": "Bearer " + token}, json=body)
        response.raise_for_status()
        return response.json()

    def call(self, name, args):
        operation = None
        try:
            if not isinstance(args, dict):
                raise ValueError("Arguments must be an object")
            if name == "chat_planner_record":
                if set(args) - {"operation_id", "text", "interpretation"}:
                    return result({"ok": False, "code": "INVALID_ARGUMENTS"}, True)
                operation = args.get("operation_id")
                if not isinstance(operation, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{16,128}", operation):
                    return result({"ok": False, "code": "INVALID_OPERATION_ID",
                                   "guidance": "新操作生成唯一编号；重试沿用原编号。"}, True)
                plan = OpenClawPlan.model_validate({"message_id": operation,
                    **{k: v for k, v in args.items() if k != "operation_id"}})
                saved = self.request("/v1/harness/plans", plan.model_dump(mode="json"))
                return result({"ok": True, "operation_id": operation, "duplicate": saved["duplicate"],
                               **visible(saved["message"]), "guidance": saved["delivery_notice"]})
            if name == "chat_planner_status":
                key = args.get("message_id")
                if set(args) - {"message_id"} or (key is not None and
                    (not isinstance(key, str) or not re.fullmatch("[a-f0-9]{32}", key))):
                    raise ValueError("Invalid status arguments")
                data = self.request("/v1/messages/" + key if key else "/v1/status")
                return result({"ok": True, **(visible(data) if key else data)})
            return result({"ok": False, "code": "UNKNOWN_TOOL"}, True)
        except ValidationError:
            return result({"ok": False, "code": "INVALID_PLAN", "operation_id": operation,
                           "guidance": "检查字段、日期时区和结束时间；本次未提交。"}, True)
        except httpx.HTTPStatusError as exc:
            code = exc.response.status_code
            advice = {423: "自动处理已暂停，本次未接收。", 409: "编号已被不同内容使用；先查询原记录，不要换编号盲目重试。",
                      401: "请启动正确的电脑服务并检查本地连接配置。"}
            return result({"ok": False, "code": "HTTP_ERROR", "http_status": code,
                           "operation_id": operation, "guidance": advice.get(code, "未确认结果；重试保持原编号和内容。")}, True)
        except (OSError, ValueError, httpx.HTTPError):
            return result({"ok": False, "code": "CONNECTION_UNCONFIRMED", "operation_id": operation,
                           "guidance": "请启动对话计划电脑服务；重试保持原编号和内容，避免重复记录。"}, True)


def dispatch(message, planner):
    def error(code, text):
        return {"jsonrpc": "2.0", "id": message.get("id"), "error": {"code": code, "message": text}}
    if not isinstance(message, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}}
    if message.get("jsonrpc") != "2.0" or not isinstance(message.get("method"), str):
        return error(-32600, "Invalid request")
    if "id" not in message:
        return None  # Notifications must never receive responses or run tools.
    method, params = message["method"], message.get("params", {})
    if not isinstance(params, dict):
        return error(-32602, "Invalid params")
    if method == "initialize":
        supported = {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"}
        requested = params.get("protocolVersion")
        value = {"protocolVersion": requested if requested in supported else "2025-11-25",
                 "capabilities": {"tools": {}}, "serverInfo": {"name": "chat-planner", "version": "0.6.0"},
                 "instructions": INSTRUCTIONS}
    elif method == "ping":
        value = {}
    elif method == "tools/list":
        value = {"tools": tools()}
    elif method == "tools/call":
        value = planner.call(params.get("name"), params.get("arguments", {}))
    else:
        return error(-32601, "Method not found")
    return {"jsonrpc": "2.0", "id": message["id"], "result": value}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root")
    args = parser.parse_args()
    root = Path(args.root).resolve() if args.root else (Path(sys.executable).resolve().parent
           if getattr(sys, "frozen", False) else Path(__file__).resolve().parents[1])
    planner = PlannerTools(root)
    # Explicit UTF-8 binary I/O also works with Windows console encodings.
    try:
        while True:
            line = sys.stdin.buffer.readline(2_097_153)
            if not line:
                break
            if len(line) > 2_097_152:
                break
            try:
                reply = dispatch(json.loads(line), planner)
            except (UnicodeError, json.JSONDecodeError):
                reply = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
            except Exception:
                reply = {"jsonrpc": "2.0", "id": None, "error": {"code": -32603, "message": "Internal error"}}
            if reply is not None:
                sys.stdout.buffer.write((json.dumps(reply, ensure_ascii=False) + "\n").encode("utf-8"))
                sys.stdout.buffer.flush()
    finally:
        planner.client.close()


if __name__ == "__main__":
    main()
