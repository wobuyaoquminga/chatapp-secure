# 贡献指南

欢迎提交可复现的问题与聚焦的修复。提交前请阅读 [README](README.md) 的安全边界和 [安全报告方式](SECURITY.md)。不要上传真实账号数据、私钥、JWT、服务器数据库、APK 签名文件或用户聊天记录。

建议先在独立测试账号和本地服务器上重现问题。修改代码时保持服务器、Windows 和 Android 对消息格式与账号状态的兼容；不要自行设计加密算法。提交 Pull Request 时说明问题触发条件、变化后的行为、数据迁移影响，以及实际执行的测试。

常用检查：服务器 `cd server && ./mvnw test`（Windows 用 `mvnw.cmd`）；桌面端 `cd client && npm ci && npm test`；Android `cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug`（Windows 用 `gradlew.bat`）。Android 构建需要 JDK 21 和 Android SDK 35。需要公网服务器的测试应使用独立部署与测试账号。
