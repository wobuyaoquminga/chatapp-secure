# Android 安装与更新

本版 Android 0.5.5（versionCode 21）属于 Chat v0.6.6 发布组合。先将服务器升级到 0.2.7，再发布客户端更新包；0.2.6 的文件上传限制配置继续保留。客户端续认证后会刷新账号期限。文件另存取消、已保存文件保留和孤立上传清理的修复仍需真机交互验收，见[验证范围](VALIDATION.md)。

## 从当前服务器更新

在设置中选择“检查当前服务器更新”，下载与当前安装相同渠道的 APK。大小、SHA-256、包名、版本码及证书校验通过后，点击安装并按系统提示确认。首次可能需要允许此应用安装未知来源应用。管理员操作见 [发布客户端更新](../deploy/ubuntu/CLIENT-UPDATES.md)。本轮服务器下载包支持 arm64-v8a；其他架构及 QA 渠道暂无适配包。

Chat 提供正式签名版和旧调试版兼容更新包。两者的安装包名、签名和本地数据独立，不能混用。

## 选择安装包

- 首次安装：使用 `Chat-Android-arm64-v8a-release.apk`。正式版包名为 `com.example.chatandroid`，使用固定的发布签名，未启用 Android 调试模式。
- 已安装旧调试版：使用 `Chat-Android-arm64-v8a-debug.apk`，包名为 `com.example.chatandroid.debug`。继续用原签名覆盖安装，保留原应用数据；不要先卸载应用。
- 正式版后续更新：选择同一包名、同一签名且 versionCode 更高的正式版包。SHA256 校验文件验证下载完整性，签名证书指纹用于核对发布身份。

正式版与旧调试版可以独立安装，但不会迁移聊天历史。在另一个安装中登录原账号会重建设备身份，旧设备身份失效，联系人需要重新核对安全码。若要保留当前聊天历史，应继续更新原安装渠道。

从可信的当前服务器或项目官方发布页下载对应包。设置中的服务器更新入口会检查并下载安装包，安装仍需用户点击并在系统界面确认；GitHub 入口用于打开发布页面。更新前阅读兼容性说明，先升级服务器，再更新客户端。

## USB 安装脚本

在源码的 `android/` 目录执行安装脚本。设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，确保 SDK 中安装 platform-tools 与 Build Tools 35.0.0。脚本要求只连接一个已授权的设备，并在安装前检查 APK 包名、版本和证书。已安装同包名时拒绝证书不匹配和降级；不会自动卸载或清除应用数据。项目正式版证书 SHA-256 见 `RELEASE-CERTIFICATE.txt` 与发布包的 `ANDROID-SIGNING.json`，这些文件只包含公开指纹。

```powershell
# 更新旧调试版；指定版本时从对应成品目录选择，不使用旧构建结果。
.\install-usb.ps1 -Version 0.6.6 -Variant Debug

# 正式版首次安装或同渠道更新。
.\install-usb.ps1 -ApkPath 'C:\path\to\Chat-Android-arm64-v8a-release.apk' -Variant Release
```

如果手机已有旧调试版，脚本会拦截单独安装正式版，说明数据不迁移。确认需要两套独立安装后才使用 `-AllowSeparateInstall`。不要用卸载旧版的方式绕过签名错误。

## 从源码构建正式版

需要 JDK 21、Android SDK Platform 35 与 Build Tools 35.0.0。签名密钥与密码必须保存在仓库外，不能提交到 Git 或打进源码归档。

通过安全的本地环境或 CI secret 注入以下环境变量：

- `CHAT_ANDROID_RELEASE_KEYSTORE`：长期使用的发布密钥库绝对路径。
- `CHAT_ANDROID_RELEASE_STORE_PASSWORD`：密钥库密码。
- `CHAT_ANDROID_RELEASE_KEY_ALIAS`：签名别名。
- `CHAT_ANDROID_RELEASE_KEY_PASSWORD`：私钥密码。

设置 `JAVA_HOME` 和 `ANDROID_HOME` 后运行 `build-release.ps1`。该脚本缺少签名配置会失败，不发布未签名 APK。输出在 `app/build/outputs/apk/release/`；应使用 SDK 的 `apksigner verify --verbose --print-certs` 核验签名，并保存公开证书指纹。自行构建的分支使用自己的发布密钥时，也需要维护自己的证书指纹；其签名身份与项目官方发布包不同。

发布私钥一旦丢失或更换，将无法按原签名覆盖更新已安装的正式版。妥善保存密钥、密码与可恢复的备份；公开证书指纹不是私钥。此签名用于 APK 安装和更新身份，不代表应用已通过安全审计或已上架应用商店。
