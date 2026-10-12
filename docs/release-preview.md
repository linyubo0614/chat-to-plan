# 0.7.1：Cloudflare 配置体验修复

本次主要更新 Windows 电脑端，修复“浏览器授权完成后一直显示正在部署，却看不到进度或失败原因”的问题。

## 修改内容

- 配置流程分为四步：检查／下载部署组件、浏览器登录、部署中转、保存中转凭据。
- 实时显示部署命令输出和等待时间，错误时明确显示“操作未完成”。
- 增加“取消配置”。超时或取消仅结束本次启动的部署进程，保留原数据与中转身份。
- 缩短无反馈等待：下载、部署及凭据写入每阶段最多 180 秒，连续 90 秒没有输出会停止；浏览器登录最多 300 秒。
- 识别未开通 workers.dev、多 Cloudflare account、网络／代理、授权等常见错误，给出对应处理提示。
- 增加过滤登录链接与凭据的 `data/relay-setup.log`，便于排查。
- 修正 Windows 的 npx 启动方式，避免误用同目录下的其他 CLI。
- 补充 Cloudflare 注册、Workers 和子域名首次开通、授权部署、手机配对及排错文档；明确 HITA 为可选扩展。

## 下载与升级

电脑请下载完整的 **chat-planner-windows.zip**，解压后运行 ChatPlanner.exe；ZIP 已包含 MCP 程序与部署文件，不需要单独拼装 EXE。

退出旧电脑程序，保留原来的整个 `data` 文件夹，替换程序文件后重新打开。此次无需重装安卓 App、HITA 或 Harness；安卓 App 的功能版本仍为 0.7.0，接口保持兼容，正常升级不用重新扫码。

发布页中的安卓 APK 和可选 HITA APK 供首次安装使用。预览 APK 使用调试签名，跨构建可能无法覆盖安装；不要为本次电脑端修复卸载手机 App。已使用 HITA 的用户重装前须导出并核对课表备份。

## 配置 Cloudflare 时注意

**注册账号和浏览器授权并不等于 Workers 已开通。** 首次使用请先在 Cloudflare 控制台进入“计算和 AI → Workers 和 Pages”，完成免费 Workers 与 workers.dev 子域名开通，再回到程序配置中转。无需购买域名。

本次修复让问题可见并支持终止等待，不保证所有网络或账号问题自动消失；根据具体输出处理即可。

[Cloudflare 完整使用说明](https://github.com/linyubo0614/chat-to-plan/blob/main/docs/cloudflare.md) · [Windows 步骤](https://github.com/linyubo0614/chat-to-plan/blob/main/docs/windows.md) · [Harness 接入](https://github.com/linyubo0614/chat-to-plan/blob/main/docs/harness.md)

## 验证

安装包复用提交 `39deb79` 的成功构建：[六项检查全部通过](https://github.com/linyubo0614/chat-to-plan/actions/runs/38231428871)。包含 63 项 Python 测试、Windows 原生 npx 启动及真实进程超时／取消测试、独立程序打包验证、安卓／MCP／中转／可选 HITA 验证。发布流程验证 APK 签名，并提供 SHA256SUMS。

个人 Cloudflare 云部署仍需在用户账号下完成；源码和文档可通过本版本标签下载。
