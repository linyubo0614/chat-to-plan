# Windows 11：连接微信、任务日历和 HITA

下载最新 Release 的 `chat-planner-windows.zip`，解压到例如 `C:\ChatPlanner`。更新时先停止旧服务并备份整个 `data` 文件夹，将程序文件覆盖到原目录，保留 `data` 和 `.venv`。旧网页测试与同步窗口无需再运行。

## 电脑服务

1. 已有 Python 3.12 不必重装；否则安装 [Python 3.12](https://www.python.org/downloads/windows/)，勾选 Python launcher。
2. 双击 `windows\start-assistant.cmd`，出现 `Uvicorn running on http://127.0.0.1:8000` 后保持窗口打开。首次安装固定依赖。
3. 双击 `windows\install-openclaw.cmd`，按 [OpenClaw 说明](openclaw.md)接入已经工作的微信 GPT-6。

无需授权其他备忘软件，也无需另开企业微信账号。电脑服务保存自己的任务和备忘；OpenClaw 继续处理微信消息，新工具执行实际保存。

## 手机 App

1. 安装最新 `chat-planner-debug.apk`。主页是任务日历，顶部切换分类备忘，右上角进入设置。
2. 保持服务运行，双击 `windows\start-test-link.cmd`。缺少 cloudflared 时按提示运行 `winget install --id Cloudflare.cloudflared --exact`，然后重开脚本。
3. 将窗口中的 `https://…trycloudflare.com` 地址填入 App 设置。双击 `windows\copy-connection-token.cmd`，私下将剪贴板口令传到手机并填写，点击连接。
4. 微信继续用 GPT-6，不需填写 DeepSeek Key。返回主页查看和编辑日历任务、分类备忘。
5. HITA 兼容版的“对话计划连接”入口填相同地址、口令，开启连接并允许通知；在 HITA 中选“对话计划”课表。

临时隧道重启后地址会变化，需要在两个 App 更新。长期使用可换固定 HTTPS 域名；不自动购买服务。8000 端口保留本机监听。

## 怎么使用

微信说“帮我记下明天交高数作业，重要但不紧急”，任务进入明天的对应象限。说“记到女朋友分类：她喜欢茉莉花茶”，内容进入分类备忘。说“明天下午三点到四点上高数，二教301”，事件等待 HITA 静默写入；时间不完整会追问。

月历点击日期查看四象限，勾选完成、点击任务编辑，也可直接新增。备忘支持自定义分类、分类筛选和标题/内容搜索。手机需联网且电脑服务在线；当前无离线新建同步、自动提醒或回收站恢复入口。

HITA 原有课表和事件编辑界面保留。“微信 / HITA”页查看消息及交付状态，补充或取消未交付日程。已写入事件可在 HITA 本地编辑；微信远程修改已有 HITA 事件未实现。

## 延迟与停止

HITA 最长25秒长轮询在任务产生时就唤醒，并非25秒检查一次。App 前台约3秒刷新。模型、网络和手机调度共同决定延迟，不承诺每次1秒必达。

设置页自动处理开关需保存生效，暂停新分析、OpenClaw 新提交和 HITA 新领取；手动编辑日历仍可用。已开始的处理可能完成。HITA 连接开关停止常驻服务；电脑 Ctrl+C 停止服务。

报错可反馈窗口错误文字和 OpenClaw 版本号；不要发送 API Key、口令、二维码、配置文件或 data 内容。
