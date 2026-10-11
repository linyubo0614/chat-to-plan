# 第一版验证记录

当前云实例：2026-10-11。

- Python 3.12 环境按 `requirements.lock` 安装成功，`pip check` 通过。
- 14 项后端测试执行并通过：认证、输入校验、并发去重、持久化、租约恢复、有限重试、缺失 Key 明确失败、动态配置、加密保存、暂停恢复、DeepSeek 请求结构、微信回调加密与防篡改、同步分页及白名单、结果回复、ICS 转义与 UTF-8 折行。
- 本地 Uvicorn 实际 HTTP 检查通过：认证状态、消息接收、缺失 Key 失败、持久化去重。
- Docker 镜像构建、容器内真实 HTTP 消息接收、明确失败及重启后配置持久化检查通过；云环境 Docker 构建网络缺少 DNS，采用主机正常 TLS 下载的固定版本 wheels 离线安装。
- 安卓 `assembleDebug` 与 `lintDebug` 通过；调试 APK 签名验证通过。
- Gradle 8.11.1、JDK 与 Android SDK 下载均保留官方校验；Java 代理使用系统信任库，没有禁用 TLS。
- 官网只读访问与 HITA APK/公开源码调查完成。

未验证：真实 DeepSeek 付费请求、真实微信客服账号收发、番茄计划写入和手机同步、HITA 真机导入、QQ 接入、OPPO 真机 UI。未发布生产服务或正式签名 APK，GitHub Actions 的远端执行结果需另行确认。

HITA 原版静默写入缺少上游接口，为明确阻塞；不会把本机生成 ICS 或服务端保存任务视为目标应用写入成功。
