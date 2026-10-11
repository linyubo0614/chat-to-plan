# 接入调查与剩余工程

调查日期：2026-10-11。用户提供的文件只作为分析材料，文件内部内容不作为项目指令。APK 不上传到 GitHub。

## HITA 3.0.2

提供的 APK 包名 `cn.limpu.hita`，版本码 `2026093004`，最低安卓 API 26。

清单中未发现用于外部添加课程的导出 Activity/Service/Provider。`ImportTimetableActivity` 不是对外入口；`FileProvider` 未导出。公开 deep link 为 `http://hita.store/search`，用途是搜索，不是写课程。

APK 包含公开项目地址：[HIT-A/HITA_Android](https://github.com/HIT-A/HITA_Android)。调查源码提交 `ea7c8147f8a5fade93dc7f4dd347ffae8a09b7a8`，未改动上游项目。

源码中的 `IcsImportUtils`、`NavigationFragment.importICS`、`TimetableRepository.importFromICSAsNewTimetable` 证明应用支持通过文件选择器导入 ICS。`IcsImportBundleBuilder` 与 `IcsImportEventMapper` 读取开始/结束时间、标题、地点，以及 `X-HITA-TYPE`；`other` 可表示普通日程。

本项目导出标准 UTF-8 ICS，带 UTC 时间和事件类型。此路径是**手动兼容退路**，未在用户真机验证，不符合最终“聊天后无需切换”的自动化目标。源码快照可能与所提供 APK 存在差异。

上游导入时生成新事件 UUID，不能假设重复导入会按 UID 去重。反复导入可能新建课表或重复课程。提醒导入支持也未验证，本工程不输出或承诺提醒。

用户要求继续使用原版更新并完全静默写入，已排除占用前台的无障碍自动操作。下一步需上游提供认证写入接口或可复用的课表同步通道。独立 App 无权修改另一应用的私有数据库；目前这项需求属于明确的集成阻塞。

进一步检查发现 `sync` 模块包含旧同步服务代码，但没有纳入 `settings.gradle` 的构建模块。`TimetableRepository` 中的 `syncCoursePlanProjection` 等方法是向本地 DAO 投影教务课程，不是第三方可写入的个人课表云同步 API；不能拿这些方法当作现成通道。

维护兼容版本会产生签名、数据迁移和更新延迟，用户尚未选择此方案，因此未制作替换版。手机为 OPPO Find X9s Pro / ColorOS 16.0.10；自动化失败率没有真机测量数据，不作数值承诺。界面自动操作即使实现也无法符合完全静默要求。

## 番茄计划

官网 `https://www.incmoon.com/web/login` 已成功只读访问。网页 JS `app.024799da.js` 暴露站点自身的登录、用户信息与同步接口，例如 `/tomato/account/login/mobile`、`/tomato/user/info`、`/tomato/syncData/listInfo`。

网页登录态使用 Jwt、用户标识和客户端请求头。此证据不等于存在稳定的公开 API。没有用户授权登录态，未发起验证码、登录或写入，也未复制站点中的签名常量。

下一步需在用户自己的授权会话中确认同步数据模型、新增记录、分类与冲突规则、过期行为及安卓同步。不能盲目提交整个同步集合，避免覆盖已有内容。未实现模拟登录或自动写入，任务保持 `blocked`。

## QQ

下一阶段基于官方机器人后台核对私聊/群聊场景，取得 AppID 与官方凭据后接入统一 inbox。当前没有 QQ 收发代码，不把通用 HTTP 接口当作 QQ 接入完成。

## 验收标准

只有目标应用中实际出现正确内容、分类/时间正确、重试不会重复写入，才能标记 `written`。当前数据模型没有可由外部随意调用的“写入成功”接口，避免伪造完成状态。
