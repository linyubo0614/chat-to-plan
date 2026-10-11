# 复用已经工作的微信 / GPT-6

用户已在 Windows 11 通过微信 ClawBot 和 OpenClaw GPT-6 对话。本项目增加实际保存工具，不更换模型，不另登录微信，不要求 DeepSeek Key。

## Windows 原生安装

1. 更新 Windows 包，保留原 `data`。双击 `windows\start-assistant.cmd` 并保持运行。
2. 双击 `windows\install-openclaw.cmd`。它寻找本机 openclaw 命令，安装本仓库插件，将两个工具追加到允许列表，保留已有模型、渠道及其他工具设置。
3. 看到 `OPENCLAW_PLANNER_INSTALLED` 后，以原来方式重启 OpenClaw gateway，无需重做微信扫码。
4. 微信开启新会话回合，发下方验收消息；手机配置见 [Windows 说明](windows.md)。

插件默认地址 `http://127.0.0.1:8000`，口令只从本机 `data/api-token` 读取，不作为模型参数或复制到聊天。插件无 npm 运行依赖。安装器需要支持 `config set --batch-file` 和运行时插件检查的 OpenClaw；版本过旧会停止，不自行升级已有 OpenClaw。开发验证使用官方 `openclaw@2026.10.1`。

## 如果 OpenClaw 实际运行在 WSL

Windows 终端找不到命令而原来通过 WSL 启动时，在原 WSL 终端运行 `openclaw --version` 并说明使用 WSL。不要搬动私有渠道配置。可在 WSL 安装：

```bash
openclaw plugins install /mnt/c/ChatPlanner/openclaw --accept-capabilities --no-enable
```

配置项为 `plugins.entries.chat-planner.enabled=true`、`config.baseUrl=手机也可访问的 HTTPS 服务地址`、`config.tokenFile=/mnt/c/ChatPlanner/data/api-token`。将 chat-planner 追加到已有 plugins.allow，将 chat_planner_record 和 chat_planner_status 追加到 tools.alsoAllow。已有严格 tools.allow 时追加到该列表；具体 agent 的工具限制也需允许。不要用示例覆盖整个已有配置。

WSL 默认网络不等于 Windows 本机回环，因此使用给手机的 HTTPS 地址；不关闭 HTTP 校验。原生安装器不会自动修改 WSL 配置。

## 验收三条消息

1. “帮我记下明天交高数作业，重要、不紧急。” App 选明天，查看对应象限。
2. “记在女朋友分类：她喜欢茉莉花茶。” App 按女朋友分类查看备忘。
3. “帮我加到 HITA：明天下午三点到四点上高数，二教301。” HITA 开连接后核对日期、时间、地点，written 才代表手机连接端已写入并读回。

稳定操作编号用于去重，网络不确定时沿用返回编号及原始内容重试。同编号不同内容返回409。App 编辑/删除后原提交重试不会覆盖或复活。新的模型工具调用默认是新操作，不能保证模型忽略重试规则时完全无重复。

技能仅在明确记录请求时调用；闲聊不自动写入。待办无日期默认今天，重要/紧急按意图分别设置，分类任意。课程、日程缺开始或结束时间会追问。微信目前实现新增及此次提交状态查询；已有记录在 App 或 HITA 中修改。重复课程、提醒尚未执行。

微信对话已工作的事实来自用户确认；新插件安装及微信到 OPPO 显示仍需以上消息验收。自动化测试见 [验证记录](validation.md)。
