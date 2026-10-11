# 微信接入现成项目调查

调查日期：2026-10-11。调查阅读了仓库代码、文档、许可文件和最近提交；没有登录用户微信、发送消息或运行第三方安装脚本。以下是接入路线调查，不是本项目已经接入微信的声明。

## 推荐：先验证腾讯微信 ClawBot / iLink

发现腾讯维护的 [Tencent/openclaw-weixin](https://github.com/Tencent/openclaw-weixin)。它通过微信扫码授权，连接微信后端收发消息，公开了 [后端协议](https://github.com/Tencent/openclaw-weixin/blob/c6643f6f0ac03574eb126f2d69569ea4267cc3c6/docs/protocol_zh_CN.md)，包括申请二维码、检查授权、长轮询收消息和发送回复。它不依赖电脑版微信的窗口操作。

这条路线应先于原有微信客服路线验证。微信客服代码仍保留；是否采用 ClawBot 取决于用户微信版本、账号实际授权结果和连续运行验证。扫码必须在自己的电脑/手机完成，不能把二维码、授权 token 或数字配对码发到聊天或 GitHub。

| 项目 | 核对结果 | 对本项目的用途 |
| --- | --- | --- |
| [Tencent/openclaw-weixin](https://github.com/Tencent/openclaw-weixin) | 腾讯仓库；扫码授权、长轮询、文本及媒体；实际 LICENSE 为 MIT，GitHub 自动识别显示 NOASSERTION；最近推送 2026-10-10 | 主要协议来源。整个插件需要 OpenClaw 和 Node.js >=22.13.0；直接复用整套插件会引入另一套运行时 |
| [zhayujie/CowAgent](https://github.com/zhayujie/CowAgent) | 原 chatgpt-on-wechat，现更名 CowAgent；MIT；支持 DeepSeek、个人微信 weixin 和微信客服；最近推送 2026-10-10 | 可先用来验证扫码和真实聊天；Python 微信通道可参考，完整框架还含任务代理等额外功能 |
| [SiverKing/weixin-ClawBot-API](https://github.com/SiverKing/weixin-ClawBot-API) | Python 文字机器人，直接对接 iLink，支持 DeepSeek；最近推送 2026-09-04；未找到 LICENSE | 参考部署和协议处理思路。没有确认代码复制/分发许可，暂不纳入本项目源码或安装包 |
| [cluic/wxauto](https://github.com/cluic/wxauto) | Windows UI 自动化；当前 README 的微信兼容范围是 3.9.X，不能据此认定适配 4.x；Apache-2.0；README 另有使用限制说明 | 普通微信桌面自动化的候选，版本限制不适合直接作为优先方案 |
| [fanyuantaier/wechatauto-replica](https://github.com/fanyuantaier/wechatauto-replica) | README 声明适配微信 4.1.12+，读取本地加密数据库，发送依赖 UIA/OCR；明确说明锁定或无响应窗口会导致发送失败；Apache-2.0 | 备选。读取聊天数据库和操作窗口的依赖较多，不符合优先稳定、少操作的方向；作者验证不等于用户真机验证 |

不采用 [fuergaosi233/wechat-chatgpt](https://github.com/fuergaosi233/wechat-chatgpt) 作为主路线：虽然关注者较多，其文档仍要求账号能登录网页版微信，最近推送为 2024-05-20，不能把历史关注度当作现在可用的证据。[PRCABK/wxauto-DeepSeek](https://github.com/PRCABK/wxauto-DeepSeek) 主要是群聊关键词演示，最近推送 2025-02-14，也未找到 LICENSE，不能直接作为稳定产品复制。

## 会话体验与待确认条件

CowAgent 的 [中文微信文档](https://github.com/zhayujie/CowAgent/blob/c8b1dd8c148c4d49449a69965f35977866110cbb/docs/zh/channels/weixin.mdx) 描述：微信客户端需要 8.0.69 或以上；扫码确认后新增机器人助理会话，可搜索“微信ClawBot”，可修改头像、备注并置顶。这些是上游文档描述，尚未在用户手机确认。备注为 DeepSeek 与更改机器人公开名称是不同能力，后者不能直接承诺。

用户随后确认手机微信版本为 **8.0.78**，可以查到该插件。版本满足上述文档条件，但没有完成本机扫码、账号权限和真实消息收发验证。

ClawBot 是微信渠道，OpenClaw 是运行框架，DeepSeek 是模型服务。腾讯官方插件安装在 OpenClaw 内，不意味着其消息只能交给某个指定模型。可以在 OpenClaw 配置 DeepSeek，也可采用 CowAgent 已实现的 `weixin` 通道与 DeepSeek 模型；本工程则可以按公开协议对接已有后端。具体路线应先完成扫码收发验证。用户也提到 QQ 有类似插件，暂只记录，仍按已确认的顺序先完成微信，再调查 QQ。

腾讯 README 确认扫码登录、凭证持久化、长轮询及微信后端消息收发，没有承诺所有账号均可开通、绝对不中断或永久登录。当前会话失效、重新扫码、限流、断网恢复和消息回复需要实际测量。第三方文档中的“无安全风险/无封号风险”不作为本项目的保证。

本项目若直接对接公开 HTTP 协议，不需要运行 CowAgent 或 OpenClaw 的完整任务代理。iLink 收消息使用出站 HTTPS 长轮询，因此这条微信入口无需像微信客服一样提供公网回调地址；电脑仍需在线。手机控制 App 和 HITA 访问电脑后端仍需要可达的 HTTPS 服务地址，这个需求没有因此消失。

## 已选择的接入路线

用户已在自己的 Windows 11 电脑将 GPT-6 接入 OpenClaw，并确认微信 ClawBot 对话可用。本工程复用这个现有入口，不另建微信机器人，也不再重复调用 DeepSeek。自己的 OpenClaw 工具把模型分析后的任务写入自有日历、分类备忘或 HITA 交付队列，详见 [安装与验收](openclaw.md)。

该用户联调结果不等于新工具已在用户电脑安装；实际微信到手机显示仍需按文档验收。本文此前的 CowAgent 等项目调查作为来源记录保留，未复制第三方项目代码。

## 可复核快照

- 腾讯源码：`c6643f6f0ac03574eb126f2d69569ea4267cc3c6`。
- CowAgent 源码：`c8b1dd8c148c4d49449a69965f35977866110cbb`。
- SiverKing 示例：`22381fe994f4b0265195861ff5bb335a41cfa22d`。
- npm 包 `@tencent-weixin/openclaw-weixin` 调查时版本为 2.4.10，元数据声明 MIT；没有执行包安装脚本。腾讯仓库 LICENSE 已单独核对，不能仅依据 npm 元数据决定许可。
