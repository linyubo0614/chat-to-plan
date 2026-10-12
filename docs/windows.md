# Windows：固定中转、一次扫码、沿用 Harness

0.7.0 使用你自己的 Cloudflare Workers 免费方案提供固定 `workers.dev` 地址。电脑主动连中转，手机无需额外 VPN，也不需要购买域名。正常重启、换网络或程序升级后沿用原地址和设备凭据。

## 首次设置

1. 完整解压 `chat-planner-windows.zip`，运行 `ChatPlanner.exe`。将旧版整个 `data` 文件夹保留在程序旁边。新版包含单独的 `ChatPlannerMcp.exe`，不要只下载主程序。
2. 电脑需已有 Node.js 20.19+（建议沿用 Harness 所用 Node 版本）。点击“配置 / 更新免费中转”，按浏览器提示登录你自己的 Cloudflare 账号并授权。若首次使用 Workers，先在 Cloudflare 控制台开通 Workers 和免费的 `workers.dev` 子域名，再重试。账号有多个 Cloudflare account 时，可先在系统环境配置 `CLOUDFLARE_ACCOUNT_ID` 指定目标账号。
3. 软件自动部署当前账号内一个独立中转。部署使用固定版本 Wrangler，首次会下载依赖，可能需要几分钟。成功后窗口显示固定 HTTPS 地址。
4. 点击“启动服务”，等待“固定中转已连接”。点击“显示手机配对二维码”。
5. 手机安装新版“对话计划”，打开“设置 → 扫码连接电脑（只需一次）”，扫描并确认。二维码五分钟有效、只能用一次。也可在扫码页粘贴电脑复制的配对链接。
6. **可选：仅需要 HITA 课程／日程同步时**，手机设置里点“一键配置 HITA 连接”，在新版 HITA 兼容版中确认，并允许通知。无需再复制地址或口令。
7. **可选：需要微信／QQ记录时**，按 [Harness 接入步骤](harness.md) 点击“加入 Harness 配置”，重启原 Harness，继续使用现有微信和 QQ。

这一步需要你在自己的 Windows 电脑完成 Cloudflare 登录；开发环境里的本地测试不会自动创建你的云账号或部署到你的账号。

不安装或不配置 HITA，也能使用任务、分类备忘、日历和重要日期。只在 App 中手动记录时，不需要 Harness；微信／QQ入口需先安装 dsh-im，参见 [README 安装提示词](../README.md#2-让-harness-自己安装-dsh-im)。

## 日常使用

保持电脑联网、ChatPlanner 和原 Harness 运行。配置完成后，打开 ChatPlanner 会自动启动后端和中转；可勾选“Windows 登录后自动启动”。最小化窗口继续运行，关闭窗口会停止本软件启动的服务。

手机保存的是固定地址和独立设备凭据，原始电脑管理口令不会放进二维码。电脑断网后按 1～30 秒间隔重连，手机前台自动刷新，HITA 后台恢复连接。无需重扫；重装／清除手机 App 数据、删除电脑 data 或撤销设备后需要重新配对。

固定地址依赖你保留当前 Cloudflare Worker、账号及 `workers.dev` 子域名。换账号、改子域名或删除 Worker 会改变连接条件；免费配额用尽时也会暂时不可用。软件不自动开通付费计划。

## 配置停在“正在部署”怎么办

授权完成只代表登录步骤结束，不代表 Worker 已部署。Windows 修复包会显示四个阶段、实时部署输出及等待秒数，并支持“取消配置”。组件下载、部署、凭据写入各最多等待 180 秒，连续 90 秒无输出会停止；浏览器登录最多等待 300 秒。超时或取消只终止本次启动的部署进程，保留原 `data`、Worker 名称与连接信息。

错误及进度会保存到程序旁的 `data/relay-setup.log`，其中会过滤登录链接与凭据。按错误提示排查：

- `workers.dev subdomain`：先在 Cloudflare 控制台的 Workers 页面完成免费子域名开通。
- `More than one account`：配置 `CLOUDFLARE_ACCOUNT_ID` 为目标 Cloudflare account ID，然后重新打开程序。
- `fetch failed`、`ECONNRESET`、`ETIMEDOUT`：检查本机访问 npm 和 Cloudflare 的网络、代理与证书。
- 授权错误：重新完成浏览器授权。

旧版会隐藏输出，单阶段最长等待十分钟；它无法根据等待时长判断具体原因。升级完整 Windows 包并保留原 `data` 后重试。若要在旧版直接查看部署错误，可在 PowerShell 中进入程序的 `data\relay-worker` 文件夹，运行 `npx --yes wrangler@4.45.0 deploy --config .\wrangler.toml`。这条命令只诊断／执行 Worker 部署，不完成凭据写入，之后仍需由程序完成配置。

## 排查与验证

- “连接检查”分别检查本机后端、固定 HTTPS 地址、最近 Harness 工具调用和可选 HITA 连接。未安装 HITA 时，“尚未收到”是正常状态，不影响基本功能。
- “写入手机测试备忘”只验证电脑到 App；看到记录不代表 Harness 已接入。
- 二维码过期／已使用：在电脑重新生成。第一次领取成功但响应丢失时也应重新生成。
- 中转离线：先看电脑“固定中转”状态；确认电脑网络可访问 Cloudflare，稍等自动重连。
- HITA 入口不存在或仍要手填：更新到 `3.0.3-planner.3` 兼容版。
- 能聊天但没有记录：检查 Harness 工具是否出现、当前 dsh-im 的预设是否允许这两个工具。

## 升级和备份

退出旧软件后，保留整个 `data` 文件夹，替换程序文件再打开。中转代码更新时先停止服务，点击“配置 / 更新免费中转”；复用原 Worker 名称、地址和中转凭据。随后重新启动。

手机预览 APK 的签名须与已安装版本一致才能覆盖。签名不兼容时，先保存未确认草稿；HITA 必须先导出并确认所有课表备份可用，再重装导入。不要直接卸载有未备份数据的 HITA。

开发版从源码启动：安装 `requirements.windows.txt` 后运行 `python -m desktop.app`。旧的临时隧道脚本仍可用于排查，但其地址会变化，不提供一次扫码长期连接。
