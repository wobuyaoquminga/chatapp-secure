# 从服务器下载客户端更新

Chat server 0.2.5 提供安装包查询与下载。客户端设置中的“服务器更新”使用当前选择的服务器；Windows 下载完整 ZIP，Android 按当前包名选择正式版或调试版。检查与下载无需登录。服务器没有发布包时显示暂无更新；旧服务器需要先升级。

## 管理员发布

使用同一版本成品中的 `Chat-Ubuntu-Deploy.tar.gz`、`Chat-Client-Updates.tar.gz` 和 `SHA256.txt`。先按 [升级步骤](UPGRADE.md)升级服务器。客户端包约数百 MB，部署包只含服务器，二者不能替代。

以下目录仅为示例。在 PowerShell 上传：

```powershell
$bundle = 'C:\path\to\Chat-v0.6.1'
scp "$bundle\Chat-Client-Updates.tar.gz" "$bundle\SHA256.txt" ecs-user@203.0.113.10:/home/ecs-user/
```

然后在 Ubuntu 上执行：

```sh
cd /home/ecs-user
test -f Chat-Client-Updates.tar.gz
sha256sum --ignore-missing -c SHA256.txt
tar -xzf Chat-Client-Updates.tar.gz
sudo bash ubuntu-deploy/publish-updates.sh /home/ecs-user/server-updates
curl --fail --show-error http://127.0.0.1:8082/api/updates/latest?platform=windows-x64
curl --fail --show-error http://127.0.0.1:8082/api/updates/latest?platform=android-arm64-v8a-release
```

发布脚本核对清单、文件长度及 SHA-256，复制到 `/opt/chat/updates/releases/` 并原子替换当前清单；不删除聊天数据，不覆盖已有版本目录。再次发布同一版本只会校验并复用已有文件；同版本内容改变会被拒绝，须提高版本重新打包。包目录由 root 管理、服务只读；`CHAT_UPDATES_DIRECTORY` 可在 `/etc/chat/chat.env` 配置。自定义目录发布时也须给脚本传同一变量，并保证 root 所有、服务账号可读。

既有 HTTPS 反向代理应把 `/api/updates/` 转发给 Chat，与其他 API 相同；不要转发到另一个站点。通常无需另开端口。公网访问需要可信 HTTPS，只有回环开发地址允许 HTTP。若代理给下载请求设置短超时，请根据带宽调整；测试前先用公网 URL 查询清单。

## 客户端操作

在设置中检查当前服务器最新版本，有新版本时下载。切换服务器后重新检查；未完成下载可取消。失败文件不会作为安装包使用。Windows 下载完成后打开所在文件夹，退出 Chat，完整解压 ZIP 后启动新的 `Chat.exe`，保留原应用数据。不要只替换 EXE。

Android 下载完成后点击安装，首次可能需要系统授权此应用安装未知来源应用；允许后返回 Chat 再点击安装。安装界面仍由系统确认。应用校验包名、更新版本码与已有安装证书；正式版和调试版不能互相覆盖，也不要卸载旧版绕过签名校验。清除应用或卸载会丢失本机历史。

SHA-256 用于发现传输损坏，不能代替发行者身份认证。Android 的安装证书检查提供渠道身份约束；Windows ZIP 目前没有代码签名，只从可信服务器获取并保留完整目录。下载不会自动启动安装包。官方 GitHub 发布页仍保留为备选入口。
