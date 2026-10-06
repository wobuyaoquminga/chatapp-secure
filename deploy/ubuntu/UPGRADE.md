# 升级到 Chat server 0.2.7

Chat v0.6.6 使用服务器 0.2.7。即使已安装服务器 0.2.6，本轮也须升级服务器，再按文末发布客户端安装包。

服务器 0.2.7 修复 WebSocket 续认证后账号活跃时间未更新的问题；同一 ID、同一密文哈希的附件重传遇到服务器磁盘密文丢失或损坏时会重新保存。本轮没有新增数据库迁移或聊天协议变更；从更早版本升级仍会执行尚未应用的迁移。已有账号、联系人、未送达密文及安装包下载功能保留。已为服务器 0.2.6 配置的 `/api/files/` 11 MiB Nginx 请求限制应继续保留。

如使用 v0.6.6 发布包，请从同一发布版本取得 `Chat-Ubuntu-Deploy.tar.gz` 与 `SHA256.txt`；也可按 [Ubuntu 部署说明](README.md)从源码构建。不要使用旧版 0.2.1 部署包。以下以 Windows PowerShell 和示例服务器地址演示上传；将本地目录、SSH 私钥和服务器 IP 换成自己的值：

```powershell
$bundle = 'C:\path\to\Chat-v0.6.6'
scp -i "$env:USERPROFILE\.ssh\id_ed25519" "$bundle\Chat-Ubuntu-Deploy.tar.gz" "$bundle\SHA256.txt" ecs-user@203.0.113.10:/home/ecs-user/
```

在 Ubuntu 24.04 上，先校验包、解压并检查安装脚本。`SHA256.txt` 还可能列出未上传的 Windows 和 Android 包；`--ignore-missing` 只跳过那些文件，部署包必须存在且校验通过：

```sh
cd /home/ecs-user
test -f Chat-Ubuntu-Deploy.tar.gz
sha256sum --ignore-missing -c SHA256.txt
tar -xzf Chat-Ubuntu-Deploy.tar.gz
bash -n ubuntu-deploy/install.sh
```

确认校验成功后，短暂停止旧服务，备份静止的 H2 数据、环境配置、旧 JAR 和服务文件，然后先恢复旧服务。这样安装脚本运行时能识别原服务处于运行状态，若新服务未通过启动检查，会尝试自动恢复旧版本。备份目录只允许管理员读取；不要把其中的密钥或数据库上传到公开位置。

```sh
sudo install -d -m 0700 /var/backups/chat
stamp=$(date +%F-%H%M%S)
sudo systemctl stop chat
sudo cp -a /var/lib/chat/data "/var/backups/chat/data-$stamp"
sudo cp -p /etc/chat/chat.env "/var/backups/chat/chat.env-$stamp"
sudo cp -p /opt/chat/chat-server.jar "/var/backups/chat/chat-server-$stamp.jar"
sudo cp -p /etc/systemd/system/chat.service "/var/backups/chat/chat.service-$stamp"
sudo systemctl start chat
sudo systemctl is-active --quiet chat
test "$(curl -sS --retry 15 --retry-connrefused --retry-delay 2 --connect-timeout 5 --max-time 10 -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/api/contacts)" = 401
```

只有旧服务恢复且本机受保护接口返回 401 后，才运行新包中的安装脚本。不要传 `--origin`，脚本会保留已有 `/etc/chat/chat.env`；TURN 服务保持原配置。已有 HTTPS 反向代理继续保留 `/api/files/` 的 11 MiB 上传限制；从更早版本首次启用文件传输时，按[文件传输说明](../../文件传输.md)增加该配置，其他路由保持原限制。

```sh
cd /home/ecs-user
sudo bash ubuntu-deploy/install.sh
sudo systemctl status chat --no-pager
curl -sS -o /dev/null -w 'HTTP_CODE=%{http_code}\n' http://127.0.0.1:8082/api/contacts
```

401 只证明未登录接口已响应。升级后还应由实际客户端登录，检查联系人、文字消息、离线重连与需要的通话路径；401 本身不能证明云端业务和通话链路正常。安装脚本在启动检查失败时恢复旧 JAR、服务文件和环境配置；默认目录下的 H2 还会从停服快照恢复数据，再尝试重启原服务。外部 PostgreSQL 或自定义数据路径不提供自动数据库回滚，失败后旧服务保持停止，须手动恢复一致的数据库与 JAR。若升级后才发现功能问题，需手动回滚。

手动回滚时停止 Chat，将上述备份的旧 JAR 复制回 `/opt/chat/chat-server.jar`，再启动并检查服务。若在新的 Shell 中执行，先把 `stamp` 改成备份文件名中的实际时间：

```sh
stamp=备份文件名中的实际时间
sudo systemctl stop chat
sudo cp -p "/var/backups/chat/chat-server-$stamp.jar" /opt/chat/chat-server.jar
sudo mv /var/lib/chat/data "/var/backups/chat/failed-data-$stamp"
sudo cp -a "/var/backups/chat/data-$stamp" /var/lib/chat/data
sudo systemctl start chat
sudo systemctl status chat --no-pager
```

从服务器 0.2.6 升至 0.2.7 不新增数据库迁移。若从更早版本升级并执行了迁移，回滚旧 JAR 时必须同时恢复升级前数据库，否则旧版本可能不了解新表结构或状态。恢复旧数据库会丢弃备份之后的新消息和账号变更。若曾修改服务文件或环境配置，可使用对应备份恢复，但应核对并保留当前有效的 HTTPS 与 TURN 设置。

本版客户端会迁移本地加密存储，不能直接降级旧客户端；服务器数据备份不能恢复客户端私钥或聊天历史。不要卸载正式 Android 应用，使用同签名 APK 覆盖更新。

## 发布客户端安装包

客户端更新入口还需要上传 `Chat-Client-Updates.tar.gz`。单独升级 JAR 不会自动获得 Windows 或 Android 安装包；请按 [发布客户端更新](CLIENT-UPDATES.md)执行。
