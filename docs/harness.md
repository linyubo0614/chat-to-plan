# DeepSeek Harness 0.2.0-rc.2：复用已有微信和 QQ

本版本使用 Harness 官方 `@deepseek-ai/dsh-mcp-client` 的 stdio 接入。沿用已经运行的 dsh-im 和模型设置，不需要再次填写 DeepSeek Key，也不需要重新授权微信或 QQ。

## Windows 原生接入

1. 完整解压 Windows 包，确保 `ChatPlanner.exe` 和 `ChatPlannerMcp.exe` 在同一个文件夹。保留原 `data`。
2. 打开 ChatPlanner，点“启动服务”。电脑后端默认只监听 `127.0.0.1:8000`。
3. 点“加入 Harness 配置”，选择**你正在使用的 profile** 的 `cordis.patch.yml`。`dsh web` 默认是 `%USERPROFILE%\.dsh\profiles\web\cordis.patch.yml`；设置过 `DSH_HOME` 时以它为准。其他 profile 或桌面客户端请选择其实际配置文件，不能照搬 web 路径。
4. 安装器仅加入标记的 `mcp-chat-planner` 配置块，保留其他插件、模型和 `!!js` 配置。已有内容先备份到程序 `data` 文件夹。重装更新该块，不重复添加。配置不是 YAML 列表或出现同名手动配置时会停止，不覆盖原文件。
5. 重启你原来的 Harness。在 dsh-im 所用会话／Agent Preset 中确认可以使用 `mcp__chat-planner__chat_planner_record` 和 `mcp__chat-planner__chat_planner_status`。如预设限制工具，需要把这两个工具加入预设。
6. 通过原来的微信或 QQ 发送下方验收消息。

只想试用时，点“生成 Harness 接入配置”，先退出正在运行的 `dsh web`，再运行：

```powershell
dsh web --patch "C:\你的路径\ChatPlanner\data\chat-planner.cordis.patch.yml"
```

`--patch` 本次启动有效。持久接入使用上面的“加入 Harness 配置”。不要同时导入生成文件和安装配置块，否则会重复注册。生成的 `chat-planner.mcp.json` 供支持标准 MCP JSON 导入的客户端使用，不能直接覆盖 Harness 的 Cordis 配置。

MCP 进程由 Harness 启动，直接读取本机 `data/api-token` 并调用本机后端。配置文件仅包含程序路径，不含口令。升级或移动整个程序文件夹后重新点击“加入 Harness 配置”，以更新绝对路径。既有记录在 SQLite 中，停机备份整个 data 即可。

## 数据路径与反馈

微信／QQ → 已有 dsh-im → 当前 Harness 模型整理 → MCP 计划工具 → 本机数据库 → 安卓 App／HITA。

- `chat_planner_record` 提交结构化事项。后端再次验证字段，在同一事务中保存，不调用第二个模型。新记录必须有唯一 operation_id；网络失败重试沿用原编号及内容。
- `chat_planner_status` 返回服务器时间、队列、连接情况；传 message_id 可查询实际交付状态。
- 待办／备忘 `written` 表示已保存到电脑数据库；HITA `written` 表示手机完成写入并读回。`blocked`／`delivering` 表示尚在等待，不能回复已写入 HITA。
- 为减少常规记录延迟，记录工具立即返回当前状态，不为了等 HITA 而阻塞数秒。可继续调用状态工具查询。

## 验收

1. 微信：“帮我记下明天交高数作业，重要、不紧急”。App 日历查看明天的待办。
2. QQ：“记在女朋友分类：她喜欢茉莉花茶”。App 分类备忘查看对应分类。
3. 任一入口：“帮我加到 HITA：明天下午三点到四点上高数，二教301”。HITA 后台连接开启后核对“对话计划”课表，再查询交付状态。
4. 电脑“连接检查”中的“最近 Harness 计划调用”应更新。能聊天不代表计划工具已经生效，以真实工具结果和数据库记录为准。

如原 OpenClaw 仍接着同一微信入口，请由你选择保留哪一个消息处理程序，避免两边同时记录。本程序默认不再启动 OpenClaw。旧接入和工具仍可按 [OpenClaw 文档](openclaw.md) 使用。

依据：官方 [MCP 客户端](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/mcp/mcp-client) 与 [用户 patch 说明](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/mcp-memory.zh.md)。本地互通验证使用 0.2.0-rc.2 所依赖的 MCP Client 2.0.0；真实微信／QQ账号与个人 Harness 预设仍需上述验收。
