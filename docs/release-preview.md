对话计划 0.5.1 预览版：修复 DeepSeek 旧模型名阻止切换的问题。

- 0.5.0 固定 deepseek-chat，但该模型已退役。新版读取账号可用列表，优先 deepseek-flash，其次 deepseek-v4-flash 或 deepseek-v4-pro，并验证实际工具调用。
- 全部助手、子助手和辅助模型统一切换到选定 DeepSeek；清除 GPT 回退，默认关闭深度思考以优先低延迟。失败时不应用模型配置，区分 Key、余额和繁忙错误。
- 连接检查分别显示微信网关、配置模型和保存工具状态；手机设置支持当前模型名，避免保存设置退回旧名。

更新 Windows ZIP 或 ChatPlanner.exe，先退出旧连接中心并保留整个 data 文件夹。启动新版，验证并应用 DeepSeek，重启微信助手，微信发 /new，再发 /status 核对实际模型。Key 只填自己的电脑软件。

手机 APK 更新后会保留电脑已选择模型。预览 APK 签名可能不能覆盖旧版，先保存未确认草稿。此次 HITA 功能无变化，无需更新 HITA；若需要重装必须先导出并确认课表备份，不能直接卸载。

[完整操作说明](https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md)。仅复用同一提交通过全部检查的构建。框架模拟模型和模拟器测试不能代替用户真实 DeepSeek/微信/OPPO验收。
