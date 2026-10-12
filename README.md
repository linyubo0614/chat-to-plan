# 对话计划

一个简约的个人任务与备忘工具。可以在安卓 App 里直接记录，也可以在微信、QQ 中告诉 DeepSeek Harness，让它整理并保存到同一个数据库。

**不安装、不配置 HITA，也能正常使用每日任务、分类备忘、日历、DDL 和考试等重要日期。** HITA 仅用于额外的课程／日程同步。

## 能做什么

| 功能 | 用法 |
| --- | --- |
| 每日任务 | 用重要／紧急四象限安排任务，支持完成、编辑和删除 |
| 分类备忘 | 自定义分类，例如高数、英语、女朋友；支持搜索和编辑 |
| 重要日期 | 记录 DDL、考试等；支持全天或具体时间、倒计时和逾期状态 |
| 月历 | 查看每天的任务，用 ◆ 标出未完成的重要日期 |
| 微信／QQ记录 | 通过 dsh-im 使用现有 Harness 模型，整理后调用本项目的保存工具 |
| 手机日历提醒 | 点击“加入日历提醒”，在手机日历中选择提醒时间并保存 |
| HITA（可选） | 将明确的单次课程、日程写入 HITA 兼容版，并核对写入结果 |

普通任务、备忘和重要日期保存在电脑数据库，安卓 App 联网查看。微信／QQ记录复用 Harness 当前模型，不额外调用第二个模型。

## 下载与准备

从 [GitHub Releases](https://github.com/linyubo0614/vibecoding_test_01/releases) 选择最新版；当前为 [0.7.0 预览版](https://github.com/linyubo0614/vibecoding_test_01/releases/tag/v0.7.0-preview)。

| 文件 | 是否需要 |
| --- | --- |
| `chat-planner-windows.zip` | 必需。电脑端完整包，包含主程序、Harness MCP 工具程序和中转部署文件 |
| `chat-planner-debug.apk` | 必需。安卓“对话计划”App |
| `HITA_Planner_3.0.3-planner.3.apk` | 可选。只有需要 HITA 课程／日程同步时才安装 |
| `SHA256SUMS` | 可选。用于核对下载文件 |

推荐环境：Windows 11 电脑、Android 8.0 及以上手机、Node.js（中转配置要求 20.19+，Harness 的版本要求以其官方文档为准）。Windows ZIP 无需安装 Python；请完整解压，不要只下载一个 EXE。

跨网络连接使用你自己的 Cloudflare 免费 Workers 中转，无需购买域名。没有账号时先 [注册 Cloudflare](https://dash.cloudflare.com/sign-up)，验证邮箱，并按控制台提示开通 Workers 与免费的 `workers.dev` 子域名。项目不会自动开通付费计划，实际使用受 Cloudflare 免费额度和网络可达性限制。

只在 App 里记录时，无需安装 Harness 或 dsh-im。希望在微信／QQ里记录时，再完成下面的助手接入步骤。

## 第一步：连接电脑与手机

1. 电脑完整解压 Windows ZIP，运行 `ChatPlanner.exe`。
2. 点击“配置 / 更新免费中转”，在浏览器登录自己的 Cloudflare 账号并授权。首次下载部署依赖可能需要几分钟。
3. 配置成功后点击“启动服务”，等待“固定中转已连接”，再点击“显示手机配对二维码”。
4. 手机安装安卓 APK，打开“对话计划 → 设置 → 扫码连接电脑”，扫描二维码并确认。
5. 回到 App，就可以添加任务、写备忘和记录重要日期。**此时不需要配置 HITA。**

二维码五分钟内有效，只能领取一次。配对成功后会保存固定地址和设备凭据；正常重启无需重新填写地址、口令或扫码。重装／清除手机 App 数据、删除电脑 `data` 或撤销设备后需要重新配对。

详细步骤与网络排查见 [Windows 连接说明](docs/windows.md)。

## 第二步：接入微信／QQ（可选）

### 1. 准备 DeepSeek Harness

先按 [DeepSeek Harness 官方说明](https://github.com/deepseek-ai/deepseek-harness/blob/master/README.zh.md) 安装并启动 Harness，完成自己的模型配置，确认能正常对话。本项目的接入验证使用 **Windows 原生 Harness 0.2.0-rc.2**；其他版本请先核对其插件与 MCP 配置方式。

如果已经能通过 dsh-im 在微信／QQ与 Harness 聊天，跳过插件安装，直接进行本节第 3 步。

### 2. 让 Harness 自己安装 dsh-im

在 **电脑上的 Harness 对话窗口**中复制发送下面的提示词。Harness 需要能够访问网络、执行安装命令及修改自己的配置；若出现操作授权提示，请按实际安装需要授权。

```text
请在这台 Windows 电脑上为我安装 dsh-im 插件，让微信和 QQ 可以连接当前的 DeepSeek Harness。
插件仓库：https://github.com/xmanrui/dsh-im

请先阅读仓库最新 README 和安装说明，检查我实际运行的 Harness 版本、Node.js、DSH_HOME、启动方式和正在使用的 profile。
按与本机版本兼容的官方方式实际执行安装，不要只列出操作步骤或命令。
上游目前推荐 npm 插件安装；如果当前使用 web profile，可以核对并采用：
dsh plugin --profile web add -w @xmanrui/dsh-im
如果当前使用其他 profile 或 Desktop，请根据实际环境选择正确的安装方式，不要直接照搬 web 配置。

修改前备份相关配置，保留现有模型、API 配置、其他插件和已接入的机器人；已经正确安装时不要重复安装。
安装完成后检查插件是否加载，告诉我是否需要重启 Harness，以及“设置 → IM机器人”的微信／QQ接入入口。
需要账号扫码或授权时提示我在本机完成，不要替我假定已登录，也不要在回复中展示密钥或登录凭据。
如果版本不兼容或安装失败，请报告实际原因及下一步，不要声称安装成功。
```

安装后按 Harness 提示重启或刷新，在“设置 → IM机器人”中选择微信或 QQ，按插件页面引导扫码／填写机器人凭据。两种渠道可以只接入其中一种。先发送一条普通消息，确认机器人能够回复，再继续下一步。

dsh-im 是独立社区插件，其安装和渠道接入以 [上游说明](https://github.com/xmanrui/dsh-im#安装) 为准。安装插件与账号扫码是两个步骤，扫码需要本人操作。

### 3. 把本项目的计划工具加入 Harness

1. 保持 ChatPlanner 服务运行，电脑点击“加入 Harness 配置”。
2. 选择当前 Harness 实际使用的 profile 的 `cordis.patch.yml`。安装器会备份并追加本项目配置。
3. 重启原 Harness。在 dsh-im 所用的会话／Agent Preset 中，确认允许使用下面两个工具：
   - `mcp__chat-planner__chat_planner_record`：保存记录。
   - `mcp__chat-planner__chat_planner_status`：查询服务器时间与处理状态。
4. 通过微信或 QQ 发送一条记录请求，在手机 App 中核对结果。

接入后沿用 Harness 已有模型与 Key，不必在本项目里重新填写 DeepSeek Key。详细 profile 路径、临时接入与排查见 [Harness 接入说明](docs/harness.md)。

## 可以这样使用

| 给助手发的话 | 在哪里查看 |
| --- | --- |
| “帮我记下明天交高数作业，重要、不紧急。” | App 的明日每日任务 |
| “记在女朋友分类：她喜欢茉莉花茶。” | App 的分类备忘 |
| “记录 DDL：10 月 20 日晚上 11 点 59 分交英语论文。” | App 的重要日期与当天任务 |
| “记下高数考试，10 月 23 日上午 9 点，地点教学楼 302。” | App 的重要日期与月历 |

没有微信／QQ接入时，以上内容也可以直接在 App 中创建。重要日期默认可选全天，明确时间时再设置；缺少日期的助手请求会追问。具体用法见 [重要日期说明](docs/important-dates.md)。

点击“加入日历提醒”后，需要在手机日历中选择提前提醒时间并保存。**通知由手机日历负责，App 自身不发送后台日期通知，也不需要 HITA 来提供这项提醒。**

## HITA 扩展（可选）

基本流程到这里已经可以使用。如果还希望把课程／日程自动写入 HITA：

1. 安装发布页中的 HITA 兼容版 APK；原版 HITA 不包含本项目的连接入口。
2. 在对话计划的设置中点击“一键配置 HITA 连接”，在 HITA 中确认，并按说明开启后台连接。
3. 在微信／QQ说：“帮我加到 HITA：明天下午三点到四点上高数，二教 301。”然后到 HITA 核对结果。

不接入 HITA 时，跳过本节。连接检查显示“最近 HITA 连接：尚未收到”不会阻止任务、备忘或重要日期保存。明确提交给 HITA 的课程／日程会等待其连接，不会自动变成本 App 的普通任务，也不能视为已写入 HITA。

当前支持单次课程／日程；重复课程自动展开、从微信修改已写入的 HITA 事件尚未实现。详见 [HITA 扩展说明](docs/hita.md)。

## 日常使用、升级与常见问题

- **电脑需要一直开着吗？** 同步和微信／QQ自动记录时，需要电脑联网、ChatPlanner 和 Harness 运行。仅手动使用 App 时不需要 Harness。最小化 ChatPlanner 可以继续运行；关闭窗口会停止它启动的服务。
- **关掉手机 App，助手还能记录吗？** 可以保存到电脑数据库；App 联网并打开后查看，前台约每三秒刷新一次。
- **能聊天却没有记录？** 聊天成功只说明 dsh-im 已连通。检查计划工具是否加载、当前机器人的预设是否允许工具，并核对实际保存结果。
- **软件升级需要重扫吗？** 正常升级保留电脑的整个 `data` 文件夹和手机 App 数据即可。电脑与安卓应使用配套版本；程序移动路径后重新点击“加入 Harness 配置”。
- **如何备份？** 停止电脑服务后备份整个 `data` 文件夹，其中包含记录、连接配置和凭据；不要把它提交到公开仓库。
- **APK 提示无法覆盖安装？** 预览版使用调试签名，跨构建可能不能直接覆盖。先保存未确认草稿；已使用 HITA 时，还须导出并核对课表备份，再处理重装。

## 开发与更多说明

后端 Python 3.12，工具测试 Node.js 24，安卓构建 JDK 21 与 SDK 35。只有构建可选 HITA 兼容版时才另需 SDK 36。

```bash
bash scripts/install.sh
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m scripts.generate_openclaw_schema --check
node --test tests/test_openclaw_plugin.mjs
bash scripts/start.sh
```

本机服务默认监听 `127.0.0.1:8000`。手机通过固定中转连接；Harness 的 MCP 工具在同一电脑直接调用本机服务。旧 OpenClaw 接口保留，可按文档单独配置。

[固定中转](docs/relay.md) · [API 与备份](docs/deployment.md) · [安卓构建](docs/android.md) · [OpenClaw 旧接入](docs/openclaw.md) · [验证记录](docs/validation.md)

项目已验证后端、Windows 独立程序、安卓构建与模拟器界面、中转/MCP 和可选 HITA 数据写入。个人 Cloudflare 部署、实际微信／QQ账号及手机通知仍需在各自设备上验收。
