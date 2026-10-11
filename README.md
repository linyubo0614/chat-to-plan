# 对话计划助手

用微信/QQ 对话整理备忘、待办和课程的个人助手，使用 DeepSeek 理解内容。安卓控制 App 用于连接自己的服务、开关分析、配置 API Key 和测试消息。

**当前是第一版可运行工程，尚未打通两款目标软件的自动写入。** 解析成功、生成日历文件都不会被标记为“已写入”。

| 组件 | 当前能力 | 尚需条件 |
| --- | --- | --- |
| Python 服务 | 认证、持久化队列、去重、租约恢复、有限重试、动态开关、加密保存 API Key | 实际部署 |
| DeepSeek | JSON 解析、拆分、分类、时间校验、缺失信息追问 | 真实 API Key 与额度；真实模型请求尚未验证 |
| 安卓控制 App | HTTPS 连接、密钥库保护口令、配置模型和 Key、开关、测试消息 | 安装 APK 并连接服务器；真机 UI 待验证 |
| 微信客服 | 加密回调验证、消息同步、白名单、结果回复 | 开通主体、客服 Secret、HTTPS 回调；真实联调待验证 |
| 番茄计划 | 网页接口调查完成 | 登录授权及同步协议验证；未实现自动写入 |
| HITA | 单次课程/日程的 ICS 导出；公开源码确认支持手动导入 | 真机导入验证；原版未发现自动写入入口 |
| QQ | 后端预留统一消息模型 | 官方机器人申请及会话能力确认；未实现 |

## 本地启动

需要 Python 3.12。使用当前 checkout；云任务已经隔离，不需要额外 Git worktree。

```bash
bash scripts/install.sh
bash scripts/start.sh
```

启动脚本只监听本机，首次生成连接口令并保存到忽略的 `data/api-token`，不会在日志里输出。将其私下填入 App 的“连接口令”。不要将口令、API Key、微信账号凭据发到聊天或提交 Git。

本地脚本不会自动读取 `.env`。可在运行环境注入变量；或者使用下方 Docker Compose 自动读取 `.env`。DeepSeek Key 也可通过控制 App 在连接后保存，不需重启服务。

```bash
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python scripts/smoke.py
```

第二条命令需要服务已启动。没有 Key 时，它验证明确失败和去重，不会伪造解析结果。有 Key 时不自动发起付费测试。

## 安卓 App

[下载首版调试 APK](https://github.com/linyubo0614/vibecoding_test_01/raw/refs/heads/main/downloads/chat-planner-0.1.0-debug.apk)。这是实际保存在 GitHub 的安装包，不依赖聊天工作区链接或 Actions 构建是否成功。

源代码在 [`android/`](android/)，Android 8.0 及以上可用。只需网络权限，不申请无障碍、联系人或存储访问。

安装后填写你的 HTTPS 服务地址和连接口令，点击“连接并读取设置”。填写 DeepSeek Key 并点击“保存设置”。空白 Key 保留现有配置，单独按钮用于移除 Key。功能开关保存后在服务端持久生效，关闭 App 不会停止服务。

关闭开关会暂停后续分析；已开始的分析可能完成。期间接收的消息保留在队列，恢复后继续处理。当前不会自动执行修改、撤销、历史追问合并或重复课程展开。

App 不是服务端：日常微信处理需要服务器保持在线。它目前不会直接向番茄计划或 HITA 写入。

构建、安装和发布说明见 [安卓构建说明](docs/android.md)。

## 服务部署

```bash
cp .env.example .env
# 在本地编辑 .env，至少配置一个随机的 ASSISTANT_API_TOKEN（不少于24字符）。
docker compose up -d --build
```

在服务器前配置 HTTPS 反向代理，转发到 `127.0.0.1:8000`。不要把 API 暴露为公共 HTTP。微信回调地址为 `https://你的域名/wechat/callback`。保持单个 Uvicorn worker；SQLite 队列适合个人部署。

`/healthz` 只检查服务存活，不代表目标软件可用。`/v1/status` 返回真实配置状态和队列统计。受保护的 API 使用 `Authorization: Bearer <连接口令>`。

所有实例互相独立；可为不同使用者部署独立服务和数据库，复用相同代码及 App。当前不是多人共享服务，连接口令具有这个实例的完整配置权限。

部署与凭据说明见 [部署文档](docs/deployment.md)，微信开通见 [微信客服接入](docs/wechat.md)，接口证据见 [接入调查](docs/integrations.md)。

## 数据与限制

- 消息、解析结果、微信同步游标及回复队列保存在 SQLite；重启后保留。
- 重复消息以来源及消息编号去重。同编号但内容不同返回冲突。
- DeepSeek Key 用 AES-GCM 加密，数据库和加密主密钥分开保存；备份必须同时保留二者。这不能抵御服务器本身被控制。
- App 使用 Android Keystore 保护连接口令，禁用应用备份和设置页截图，不持久保存 DeepSeek Key。
- 未验证的写入全部保持 `blocked`，不回报成功；需要时间信息的内容保持 `needs_clarification`。
- ICS 只导出时间完整、没有未解决问题的单次事件；不会猜测时长，不导出未验证的重复规则或提醒。
- 原始消息会发送到 DeepSeek；服务器保存个人消息，备份和设备访问应由部署者管理。
- 不依赖个人微信非官方协议，不声称能保证外部平台或安卓后台永不中断。

GitHub Actions 会运行后端测试，并构建、上传调试 APK。调试包用于验证，正式分发前需自行签名和真机测试。
