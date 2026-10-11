对话计划 0.3.0 / HITA 3.0.3-planner.1 联调预览。

这次增加实际交付端：

- 番茄计划：Windows 本地官网浏览器授权，新增分类和备忘/待办，写入后读回核对。
- HITA 兼容版：用户开启常驻通知，长轮询接收任务，静默事务写入本地“对话计划”课表，读回后确认。
- 服务端：交付租约、断线恢复、重复写入保护和结果确认；控制 App 显示交付与写入状态。
- Windows 11：启动服务、官网测试写入、持续同步、私下配置 DeepSeek/微信，以及临时 HTTPS 联调脚本。

下载 `chat-planner-windows.zip` 解压，先按 [Windows 操作步骤](https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md)运行 `windows/start-assistant.cmd` 和 `windows/test-tomato.cmd`，在自己电脑的官网登录并核对测试记录。不要把密码或 Key 发到聊天。

手机安装 `chat-planner-debug.apk`（控制端）及 `HITA_Planner_3.0.3-planner.1.apk`（独立兼容版）。保留原版 HITA，不自动迁移其私有数据；使用已有导出/导入功能前先备份并核对。

秒级是在线交付目标，25 秒长轮询可在新任务产生时立即返回，不是 25 秒的定时轮询。尚未测量真实 DeepSeek、微信、官网手机同步和 OPPO 端到端延迟；Windows 原生脚本和真机仍需本地验收。微信主体/客服和固定 HTTPS 回调仍需要用户开通配置，QQ 延后。

仅支持信息完整的单次事项。重复规则、指定提醒和已写入记录的修改/撤销尚未接入。官网接口可能随网站更新变化；失败不会被标记为成功。

附件从本次 CI 构建和检查产物发布，提供 `SHA256SUMS`。这些是调试签名预览包，固定签名尚未配置，跨构建机器可能不能覆盖安装；卸载会清除对应 App 的本地数据，HITA 数据必须先备份。
