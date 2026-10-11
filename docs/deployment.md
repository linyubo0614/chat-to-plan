# 服务部署与复用

## 运行方式

Windows 11 用户直接按 [本机部署与 OpenClaw 连接](windows.md)运行发布附件中的脚本，无需先学习 Docker。

Python 3.12，直接依赖在 `requirements.txt` 中声明，安装使用固定完整依赖版本的 `requirements.lock`。`bash scripts/install.sh` 创建 `.venv`；`bash scripts/start.sh` 启动一个进程和一个后台工作线程。调试用本机地址不作为公开网页预览。

如果 Docker 构建网络不能解析 PyPI，可在与目标容器兼容的 Linux/Python 3.12 主机上执行 `.venv/bin/python -m pip download -r requirements.lock -d .wheelhouse`，再构建。Dockerfile 会从经正常 TLS 下载的本地 wheels 离线安装；无需关闭 TLS 验证。`.wheelhouse` 不提交 Git，也不打包进最终镜像。

Docker Compose 方式使用 `.env`，数据位于 `assistant-data` 持久卷。生产环境需自己的云主机/常开设备、域名和可信 HTTPS 证书。此工程没有代购或部署任何付费服务器。

反向代理应限制请求体（建议 32 KB）、限速，禁止记录 Authorization、请求正文和微信回调 query string；应用服务器已关闭 access log。外网只需 HTTPS 443，8000 仅本机开放。

## 凭据

| 名称 | 用途 | 配置方式 |
| --- | --- | --- |
| `ASSISTANT_API_TOKEN` | App 对个人实例的完整管理权限 | 部署者生成随机口令并私下填写到 App |
| `DEEPSEEK_API_KEY` | 调用 DeepSeek | 环境注入或 App 安全设置；App 设置覆盖环境值 |
| `WECHAT_CORP_ID` | 微信回调接收者及 token 请求 | 微信客服后台 |
| `WECHAT_KF_SECRET` | 调用客服 API | 环境注入，不能进入 Git |
| `WECHAT_CALLBACK_TOKEN` | 微信回调签名验证 | 环境注入，与后台一致 |
| `WECHAT_ENCODING_AES_KEY` | 本地解密微信回调 | 真实本地密钥，不可用仅在代理上替换的占位值 |
| `WECHAT_ALLOWED_USERS` | 允许使用服务的 external_userid | 逗号分隔；空值拒绝所有客户消息 |

不要将微信客服 Secret 和回调密钥混淆。回调解密必须在本地使用真实密钥。`.env` 应设置文件权限 600；如果通过云环境代理注入凭据，先验证实际请求，不要在日志打印变量值。

需要的网络目的地：`api.deepseek.com`、`qyapi.weixin.qq.com`。安装用 PyPI；安卓构建用 Google Maven、Maven Central 和 Gradle 官方分发站点。

## 开关与动态配置

`GET /v1/settings` 返回开关、模型、时区以及 Key 是否配置，不返回 Key。

`PUT /v1/settings` 接受 `enabled`、`deepseek_api_key`、`model` 的部分更新。省略或 null 的字段保留；空字符串 Key 会清除有效 Key（包括覆盖环境注入的值）。只支持 `deepseek-chat` 和 `deepseek-reasoner`。保存成功不代表 Key 已通过供应商验证，使用 App 测试消息可验证实际分析。

分析失败后，可修复配置，再对原消息调用 `POST /v1/messages/{id}/retry`。成功解析的消息不能重试，防止生成重复任务。微信回复队列与分析状态独立；可通过消息详情的 `reply` 检查回复是否失败。

## 消息历史与待写入事项

- `GET /v1/messages?limit=20&before=<cursor>`：返回本人消息的时间、来源、最多300字预览、状态和事项状态计数。`next_cursor` 为 null 时没有下一页；分页使用不可变的消息顺序编号，新消息不会造成旧页重复。limit 范围1–50。
- `GET /v1/messages/{id}`：包含完整原文、解析事项及每项的 `revision`。
- `PUT /v1/tasks/{id}`：提交 `{ "revision": 0, "item": <完整 Item> }`，验证分类和时间后替换助手中的待写入事项。只允许 blocked/needs_clarification；不会伪造写入状态。
- `DELETE /v1/tasks/{id}?revision=0`：取消待写入事项并保留原文、记录内容；不是删除目标软件内容。取消后不能导出 ICS。

两种事项更新均原子检查 revision，不匹配返回409。所有接口要求实例连接口令并限定 owner。数据库自动为旧 tasks 表添加 revision，保留原有数据。手机端也需要与0.2.0后端一起更新。

## 备份与恢复

备份前停止服务，复制 `assistant.db` 和 `credential.key`；如使用在线 SQLite 备份工具则按其事务约定操作。不要只复制活跃主数据库而忽略 WAL。保留环境变量配置和连接口令。数据备份、密钥、APK、个人账号信息不进入 GitHub。

发布云环境快照不会保留运行中的进程。后续任务必须按已保存启动说明重新启动并验证 `/healthz` 和认证状态。当前未验证新任务快照恢复。

## 自有日历与分类备忘

微信 OpenClaw 当前模型通过 `POST /v1/openclaw/plans` 提交已结构化内容，模型 Key 在电脑连接中心配置，不进行第二次模型调用。一次消息及其待办、备忘在同一事务中提交。课程、日程保留 HITA 租约交付。

- `POST /v1/planner/records`：新增，要求客户端稳定的 32 位十六进制 `record_id`。相同编号和原始内容重试返回当前记录，不覆盖后来编辑，不复活已删除记录；内容不同返回409。
- `GET /v1/planner/records?kind=todo&day=2026-10-12`：当天任务。`kind=memo` 支持 `category` 和 `q` 搜索；分页使用 `limit`（1–200）及 `before=next_cursor`。
- `GET /v1/planner/calendar?month=2026-10`：每一天的总数和已完成数。
- `GET /v1/planner/categories?kind=memo`：已有分类及记录数。
- `GET /v1/planner/records/{id}`：单条记录。
- `PUT /v1/planner/records/{id}`：完整 `PlannerRecord` 加当前 `revision`，原子检查版本。
- `DELETE /v1/planner/records/{id}?revision=0`：软删除并保留去重标记。

字段为 kind、title、body、category、important、urgent、task_date、completed；category 可自定义。待办未指定日期时默认服务时区的今天。备忘没有日历日期。API 均认证并限定实例 owner。手动日历编辑不受自动分析开关影响。数据存储在原 `assistant.db`，请保留整个 `data` 目录。
