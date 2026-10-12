# Cloudflare 使用说明：首次开通、授权部署与排查

Cloudflare 在本项目中提供一个固定的 HTTPS 中转地址，让手机能跨网络连接自己的 Windows 电脑。你需要自己的 Cloudflare 账号，但**不需要购买域名**。HITA 是可选扩展，是否安装 HITA 不影响这套连接流程。

**注册账号、浏览器授权、开通 Workers、部署本项目中转是不同步骤。授权成功不等于配置完成。**

## 一、注册账号并完成 Workers 首次开通

1. 在运行 ChatPlanner 的电脑上打开 [Cloudflare 注册页](https://dash.cloudflare.com/sign-up)，注册账号并验证邮箱；已有账号直接登录。
2. 进入 [Cloudflare 控制台](https://dash.cloudflare.com/)，选择自己的账号，打开“计算和 AI（Compute & AI）→ Workers 和 Pages（Workers & Pages）”。界面语言与版本可能不同，以 Workers 入口为准。
3. 按首次使用引导开通 **Workers Free**，完成 `workers.dev` 子域名注册或确认。若控制台提示添加网站域名，可以跳过网站托管流程，进入 Workers；本项目不要求添加自己的网站。
4. 如果页面只显示“创建应用／创建 Worker”，可按引导创建并部署一个免费的 Hello World Worker，完成首次开通。确认它有一个类似 `https://hello-world.你的子域名.workers.dev` 的地址。
5. 此时账号才具备部署固定中转的前置条件。这个 Hello World 地址只用于确认开通成功，**不要把它填到手机 App**；本项目中转地址由 ChatPlanner 稍后自动生成。

只注册账号、只点击 OAuth 授权、只创建尚未部署的 Worker，都不能作为配置完成的标志。官方操作参考：[通过控制台创建 Worker](https://developers.cloudflare.com/workers/get-started/dashboard/)。

## 二、在电脑程序中授权和部署

电脑需要 Node.js 20.19 或更高版本，完整解压 Windows ZIP 后运行 `ChatPlanner.exe`。首次配置期间电脑需要能访问 npm 与 Cloudflare。

点击“配置 / 更新免费中转”，新版程序会显示四个阶段：

| 阶段 | 你需要做什么 | 完成标志 |
| --- | --- | --- |
| 1/4 检查并下载部署组件 | 等待首次下载；这里尚未进行账号授权 | 程序进入登录阶段 |
| 2/4 Cloudflare 登录 | 在自动打开的浏览器中登录、选择账号并授权；完成后回到程序 | 程序进入部署阶段 |
| 3/4 部署固定中转 | 等待程序把中转部署到自己的账号 | 部署返回本项目的 `workers.dev` 地址 |
| 4/4 保存中转连接凭据 | 等待程序完成凭据写入 | 程序显示“固定中转已配置”以及固定 HTTPS 地址 |

**只有第四步成功才算配置完成。** 本项目部署的 Worker 名称形如 `chat-planner-xxxxxxxxxxxx`；不需要手工创建这个名称的 Worker、复制 API Token 或修改程序代码。

新版实时显示部署输出，每 15 秒更新等待时间，可以点击“取消配置”。组件下载、部署和凭据写入每阶段最多等待 180 秒，连续 90 秒无输出会停止；浏览器登录最多等待 300 秒。首次下载耗时受本机网络影响，超时后排查网络再重试即可。

旧版 0.7.0 发布包可能只显示“正在部署固定中转，首次可能需要几分钟…”，隐藏命令输出并等待最长十分钟。遇到这类等待，请使用 [最新版发布页的 Windows 完整包](https://github.com/linyubo0614/chat-to-plan/releases/tag/v0.7.2-preview)；先保留原 `data`，再替换程序文件。下载 `chat-planner-windows.zip` 后完整解压使用。安卓 App 无需为这次修复重新安装。

## 三、启动服务并给手机配对

1. 配置完成后点击“启动服务”，等待“固定中转已连接”。
2. 点击“显示手机配对二维码”。
3. 手机打开“对话计划 → 设置 → 扫码连接电脑”，扫码并确认。二维码五分钟有效，只能使用一次。
4. 在电脑点击“写入手机测试备忘”，到手机分类备忘中核对；再单独按 [Harness 接入说明](harness.md) 验证微信／QQ记录。

Cloudflare 中转配置成功不代表 Harness 工具已经接入，也不代表 HITA 已配置。手机的任务、备忘、DDL 和重要日期可先独立使用；需要 HITA 时再配置其扩展。

## 四、日常使用与升级

- 保持电脑联网和 ChatPlanner 运行，微信／QQ记录另需 Harness 运行。Cloudflare 提供中转，电脑仍负责保存和处理记录。
- 配置成功后重新打开程序会启动后端与中转，断线会自动重连。正常重启不用重新登录 Cloudflare、重新部署或扫码。
- 升级时保留程序旁整个 `data` 文件夹。重新部署会复用已有 Worker 名称和连接信息。
- 保留当前 Cloudflare 账号、Worker 和 `workers.dev` 子域名；删除 Worker、换账号或更改子域名会影响已有连接。
- 使用 Workers Free 时受其免费配额限制。本项目不自动开通付费计划；Cloudflare 额度与 Harness 模型的用量／费用是两回事。

## 手机不开 VPN 无法访问 workers.dev

如果手机开启 VPN 时 `/healthz` 正常，关闭后打不开，当前入口在该网络下不可直连。0.7.2 可以按 [自定义域名说明](custom-domain.md) 购买域名、接入 Cloudflare 并绑定原 Worker。绑定后先在手机不开 VPN 实测，验证成功再重新扫码；购买域名本身不保证可达。

## 五、配置失败时怎么处理

| 提示或现象 | 检查与处理 |
| --- | --- |
| `workers.dev subdomain` 或 `workers/onboarding` | 回到控制台完成本页第一节的 Workers 和子域名首次开通，再重试 |
| 浏览器显示授权成功，程序停在部署 | 授权仅完成第二阶段。看程序下方部署输出；先确认 Workers 已开通，再检查网络与账号选择 |
| `More than one account` | 账号关联多个 Cloudflare account，需要明确部署到哪个账号，方法见下方 |
| `fetch failed`、`ECONNRESET`、`ETIMEDOUT`、`ENOTFOUND` | 检查运行程序这台电脑访问 npm 和 Cloudflare API 的网络、代理及 DNS；浏览器可访问并不代表 Node.js 命令也能访问 |
| 证书错误 | 检查本机时间、代理与证书配置，根据具体错误修复 |
| `Authentication error`、授权过期 | 重新点击配置，在浏览器完成授权 |
| 未找到 Node.js / npx | 安装满足版本要求的 Node.js，关闭并重新打开程序后再配置 |
| 已配置但中转离线 | 点击“启动服务”，检查电脑网络和程序状态；等待重连或点击“连接检查” |

新版的配置日志在程序旁 `data/relay-setup.log`，其中会过滤登录链接与凭据。反馈时提供失败阶段和相关错误文字即可；不要上传整个 `data` 文件夹、`relay.json`、OAuth 配置或登录口令。

### 多账号时指定 Account ID

在 Cloudflare 控制台找到目标账号的 **Account ID**（不是网站 Zone ID），然后在程序目录打开 PowerShell：

```powershell
$env:CLOUDFLARE_ACCOUNT_ID = "你的目标账号ID"
.\ChatPlanner.exe
```

先关闭已经打开的 ChatPlanner，再从这个终端启动；变量只对该终端启动的程序生效。只有一个账号时通常不需要设置。

### 旧版如何直接查看部署错误

在程序的 `data\relay-worker` 文件夹打开 PowerShell，运行：

```powershell
npx --yes wrangler@4.45.0 deploy --config .\wrangler.toml
```

终端会显示实际部署错误。这条命令只诊断／执行 Worker 部署，**不会完成本项目的中转凭据写入**；排查后仍需回到程序完成配置。

[返回 README](../README.md) · [Windows 使用步骤](windows.md) · [中转技术说明](relay.md)
