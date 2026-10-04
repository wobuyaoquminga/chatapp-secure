# Android 验证范围

Android 0.5.1（versionCode 17）已通过 65 项 JVM 测试、lintDebug、debug 与正式签名 release 构建。新增更新策略测试覆盖渠道、版本、路径、哈希和证书集合校验；此前的消息与期限回归继续通过。服务器下载及系统安装的完整交互尚未完成真机验收。

正式版包名为 `com.example.chatandroid`，使用独立发布签名；旧调试版为 `com.example.chatandroid.debug`，沿用旧证书。两条安装渠道数据独立；APK 校验不等于已在手机安装或验证覆盖更新。

本版尚未完成真实设备切网、长列表帧率、键盘、快速会话切换和通话小窗专项复测。应用被系统停止后不保证消息、来电或期限提醒。JVM 测试及预览不能证明远端音视频可用。

详见 [项目验证范围](../VALIDATION.md)及 [签名与渠道说明](RELEASE.md)。
