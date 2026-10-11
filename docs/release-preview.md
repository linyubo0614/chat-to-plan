对话计划 0.4.0：自己的任务日历和分类备忘，复用微信 OpenClaw / GPT-6，保留 HITA 兼容版。

- 安卓主页改为月历和四色象限，可编辑日期、重要/紧急、完成状态及删除。
- 分类备忘支持任意分类、搜索、编辑和删除。
- 新增 OpenClaw 工具与 Windows 安装器，现有模型直接整理保存，无需 DeepSeek Key。
- 移除旧网页适配器和登录测试；助手旧队列中的待办/备忘迁到自有数据库，不读取其他软件数据。
- HITA 后台写入、读回确认、常驻通知和本地编辑功能保留。

下载 `chat-planner-debug.apk` 和 `chat-planner-windows.zip`，更新电脑程序时保留整个 data 文件夹。启动 `windows\start-assistant.cmd`，运行 `windows\install-openclaw.cmd`，成功后按原方式重启 OpenClaw。[Windows 操作步骤](https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md)。已有 HITA 兼容版连接可继续使用，无需为新日历重装。

发布只复用本提交通过全部检查的构建。模拟器验证不代替真实微信和 OPPO 联调。暂不支持离线新建同步、重复课程、指定提醒或微信修改已有 HITA 事件；事件可在 HITA 内编辑。调试签名可能变化，安装冲突时先保留未确认草稿，服务器日历和备忘不随 App 卸载删除。
