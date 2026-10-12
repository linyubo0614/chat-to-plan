# 安卓安装与构建

## 安装

在 [Releases](https://github.com/linyubo0614/chat-to-plan/releases) 下载手机本地版 `chat-planner-debug.apk`，允许安装该来源的 APK。支持 Android 8.0 及以上。

第一页是内置 DeepSeek 对话，计划和备忘页面使用相同简约风格。配置自己的 API Key 后即可用自然语言记录；不配置 Key 也可以手动管理本地数据。App 允许截屏，不需要摄像头或电脑配对。

## 本地构建

准备 JDK 21、Android SDK 35 和 Build Tools 35.0.0，使用仓库内 Gradle Wrapper：

```bash
python3 scripts/build_android.py --sdk /path/to/sdk --jdk /path/to/jdk
```

输出：`android/app/build/outputs/apk/debug/app-debug.apk`。需要编译模拟器测试包时加 `--instrumentation-build`。

```bash
cd android
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon connectedDebugAndroidTest
```

仪器测试需要已经启动的设备／模拟器。测试使用模拟 API 响应，验证操作结果及追问流程，不发送真实 Key，也不消耗 DeepSeek 余额。没有安装 HITA 兼容版时，其跨 App 测试允许跳过；CI 的 HITA 专项任务会安装两款 APK，强制验证接口。

## 签名

HITA 本地接口采用签名权限，因此两款 APK 必须由同一证书签署。本地构建脚本创建并复用未跟踪的 `.build-cache/preview.keystore`；也可以用 `PLANNER_PREVIEW_KEYSTORE` 指向本地测试密钥文件。不要把密钥文件提交到仓库。

CI 在临时目录生成测试密钥，发布的随手记事和 HITA 两款 APK 在同一个作业中使用该密钥构建。密钥不上传为 Artifact，也不进入 Gradle 缓存。同一次发布的两包签名匹配；不同构建之间可能不同，不能保证覆盖安装。

Android 提示签名不兼容时，先导出记录备份；HITA 也应先导出并核对课表／日程备份，然后再卸载重装。

正式发行时，使用开发者自己的私有证书，通过受保护的 Secrets 提供给构建环境，同时签署两款 App。妥善备份私有密钥；更换证书无法覆盖已有不同签名的安装。
