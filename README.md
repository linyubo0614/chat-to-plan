# 对话计划

在微信或 QQ 里随手说，沿用 dsh-im 接入的 DeepSeek Harness 整理成任务、分类备忘、DDL、考试日期或 HITA 日程。安卓 App 是自己的日历和备忘软件，也能配置连接和开关。电脑使用 Windows 11 的 ChatPlanner.exe，保持联网、软件运行即可。

## 下载与使用

[GitHub Releases 下载](https://github.com/linyubo0614/vibecoding_test_01/releases)：`chat-planner-debug.apk`、`chat-planner-windows.zip`（内含 ChatPlanner.exe 和 ChatPlannerMcp.exe，免 Python）；需要自动课表时使用 `HITA_Planner_3.0.3-planner.3.apk`。已有助手数据请保留整个 `data` 文件夹。

1. 按 [Windows 步骤](docs/windows.md) 启动电脑服务并连接手机 App。
2. 按 [Harness 步骤](docs/harness.md) 加入计划工具，继续使用已有微信、QQ 和模型。
3. 按 [HITA 说明](docs/hita.md) 开启兼容版的常驻后台连接。

| 功能 | 当前实现 |
| --- | --- |
| 任务日历 | 独立日历与每日任务页面、重要/紧急四象限、完成标记、编辑日期及删除 |
| 重要日期 | DDL、考试和其他重要日期；全天或具体时间、倒计时、逾期标记、编辑与完成、加入手机日历提醒 |
| 分类备忘 | 任意分类，例如女朋友、高数、英语；查看、搜索、编辑、删除 |
| 微信／QQ记录 | Harness MCP 工具接收当前模型整理结果，待办和备忘在同一事务中保存；不再调用另一模型 |
| HITA | 保留兼容版课表、原有本地编辑界面及常驻连接；单次课程、日程静默写入并读回确认 |
| 复用与管理 | HTTPS 连接、Android Keystore 保护口令、自动处理开关、消息记录、版本冲突保护 |
| 固定连接 | 自有 Cloudflare workers.dev 中转；手机首次扫码保存设备凭据，HITA 一键配对 |

App 前台约3秒读取新记录；关闭 App 后微信仍可写入电脑数据库。待办和备忘的 written 表示数据库已保存；HITA 的 written 表示手机连接端报告写入并读回。重要日期可通过系统日历设置提醒；App 自身不发送后台日期通知。重复课程展开，以及微信修改已写入 HITA 事件尚未实现；可在 HITA 内使用原有编辑界面。

## 开发与部署

Python 3.12、Node 24、JDK 21、Android SDK 35；HITA 构建另需 SDK 36。云任务使用当前 checkout，不需要新 Git worktree。

```bash
bash scripts/install.sh
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m scripts.generate_openclaw_schema --check
node --test tests/test_openclaw_plugin.mjs
bash scripts/start.sh
```

服务默认仅监听本机，首次生成 `data/api-token`，不输出口令。手机需要可达的 HTTPS 地址；Windows 软件配置固定 workers.dev 中转并自动重连。Harness MCP 与服务在同一电脑时使用本机地址，不需要给微信提供公网回调。

微信和 QQ 沿用 Harness 的模型与 Key，项目不重新配置。旧 OpenClaw 工具接口保留。App 消息分析测试和保留的微信客服方案可选 DeepSeek；没有 Key 会明确失败。凭据只在自己设备配置，不发送到聊天或提交 Git。数据保存在自己的 SQLite 服务；备份请停止服务后复制整个 data 文件夹。

[重要日期](docs/important-dates.md) · [固定中转](docs/relay.md) · [API 与备份](docs/deployment.md) · [安卓构建](docs/android.md) · [验证记录](docs/validation.md) · [接入调查](docs/integrations.md)

GitHub Actions 检查后端、原生 Windows 安装器、安卓构建与模拟器界面、HITA 原有测试及 Room 写入。预览 APK 为调试签名，跨构建可能无法覆盖安装；不要直接卸载有未确认草稿的旧版本。模拟器验证不代替 OPPO 真机及自己的微信端验收。
