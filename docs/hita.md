# HITA 可选本地扩展

不安装 HITA 也能完整使用随手记事的任务、备忘、DDL、重要日期、日历和本地提醒。只有希望查询或编辑 HITA 中的课程／日程时才需要本扩展。

## 安装与授权

1. 从随手记事同一次 Release 下载并安装 `HITA_Planner_*.apk`。
2. 在随手记事设置中打开 HITA 授权入口，到 HITA 确认本地访问。
3. 返回对话页，明确指定 HITA，例如“帮我查一下 HITA 明天的日程”。

兼容版使用独立包名 `cn.chatplanner.hita`。原版 HITA 没有该接口，也不会自动迁移原版私有数据库；已有用户应先使用 HITA 自身的导出／导入功能迁移并核对数据。

确认授权后，两款 App 通过同机 Android 服务接口通信。只需首次授权；不扫描网络二维码，不填写服务地址，不运行电脑或后台中转。可以在 HITA 撤销授权。

## 支持的操作

查询、新增、修改和删除单次日程／课程，反馈实际结果。存在多个匹配对象时需要选择目标；缺少必要日期、开始／结束时间等字段时先补充。课表有时间段等格式要求，不符合 HITA 格式的内容会提示调整。

模型负责理解请求，HITA 负责自己的数据库写入。随手记事不能直接读取原版 HITA 的私有数据库。重复规则和高级课表批量操作请使用 HITA 自身界面，避免把未支持的操作当成已经完成。

AI 理解需要手机联网访问 DeepSeek；已经授权的本地 HITA 接口本身不依赖网络。

## 可复现构建

上游项目：[HIT-A/HITA_Android](https://github.com/HIT-A/HITA_Android)，采用 MIT 许可。固定来源、提交和版本见 `integrations/hita/upstream.json`；上游许可证保留在 `integrations/hita/LICENSE.upstream` 和 APK 内。

准备 Git、Python 3、JDK 21、Android SDK 36 与 Build Tools 35.0.0：

```bash
python3 scripts/build_hita.py --output /tmp/hita-planner \
  --sdk /path/to/sdk --jdk /path/to/jdk
```

脚本创建全新目录，检出固定提交，校验并应用补丁，再添加 overlay。不会重置已有源码，不会把个人模型 Key 放入上游 BuildConfig。输出：`/tmp/hita-planner/app/build/outputs/apk/debug/app-debug.apk`。

预览构建通过 `PLANNER_PREVIEW_KEYSTORE` 使用本地指定测试密钥，未指定时创建并复用仓库未跟踪的 `.build-cache/preview.keystore`；本地签名权限要求两包证书一致。CI 在同一个作业中临时生成测试密钥，构建并验证两包，私钥不上传。不同 CI 构建之间可能无法覆盖安装，升级前先备份。CI 验证 HITA 上游单元测试、Room 写入和真实跨 App 接口。
