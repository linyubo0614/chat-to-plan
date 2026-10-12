# 固定 Cloudflare 中转

新用户先阅读 [Cloudflare 使用说明](cloudflare.md)，完成 Workers 与 `workers.dev` 首次开通、授权部署和手机配对。本页介绍中转实现与开发验证。

每个 Windows 安装使用独立 Worker 和一个 Durable Object。公共 `/v1/*` 请求转发给电脑已有后端，后端验证设备凭据；`/bridge` 只接受中转密钥认证的电脑 WebSocket。后端仍只监听本机。中转不持久保存任务正文、模型 Key 或手机凭据；HTTPS 在 Cloudflare 终止，中转处理期间能接触请求内容。

`relay/wrangler.toml` 使用 SQLite Durable Object 迁移，适用于 Workers Free。安装器固定 Wrangler 4.45.0，不自动改变账号计费方案。免费额度、Cloudflare可达性及电脑在线状态仍影响可用性。

## 生命周期

- 固定地址与中转密钥保存在本机 `data/relay.json`，服务端密钥使用 Wrangler Secret 写入。重新部署复用原 Worker 名称，程序升级保留 data。
- Durable Object 使用可休眠 WebSocket；重启或中转失去状态后，电脑重连即可。内存中未确认请求返回 503／504，写操作由各自的 operation_id／revision／交付租约防止重复或覆盖。
- 每个中转最多 32 个未完成请求，电脑最多 8 个并发后端请求；请求／响应正文上限各 512 KiB。每请求最多等待 40 秒；HITA 后端长轮询最多 25 秒。
- 转发只支持 GET、POST、PUT、DELETE 以及必要内容类型和授权头。没有任意目标 URL，也不会把电脑管理口令注入来自手机的请求。

## 配对 API

| 接口 | 认证 | 含义 |
|---|---|---|
| POST `/v1/pairings` | 已授权设备或电脑口令 | 生成五分钟单次票据，同标签新票据使旧票据失效 |
| POST `/v1/pairings/claim` | 高熵票据 | 原子领取独立设备凭据 |
| GET `/v1/devices` | 已授权 | 列出设备 ID 和标签，不回传凭据 |
| DELETE `/v1/devices/{id}` | 已授权 | 撤销该设备，其他设备继续连接 |

数据库只保存票据和设备凭据的 SHA-256 摘要。设备凭据当前具有同一实例的管理权限，安卓端使用 Keystore 加密保存。HITA 通过单次票据独立领取凭据，不复制主 App 的长期凭据。正常服务重启保留配对；请求已成功领取但客户端没收到结果时，需重新生成票据。

## 本地验证

```bash
npm install --prefix /tmp/planner-runtime wrangler@4.45.0 @modelcontextprotocol/client@2.0.0
/tmp/planner-runtime/node_modules/.bin/wrangler dev --config relay/wrangler.toml --local --port 8791 --var BRIDGE_SECRET:synthetic-local-bridge-test
# 另一个终端，8000 端口保持空闲
MCP_SDK_ROOT=/tmp/planner-runtime NO_PROXY=127.0.0.1,localhost python -m scripts.verify_relay --mcp
```

验证器创建临时数据库、真实 HTTP 服务与出站 WebSocket，检查配对、认证、HITA 长轮询及回执、重启恢复、MCP发现／调用和重试防重复。它不部署到云端，不接入个人微信或 QQ。
