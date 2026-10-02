# 升级到 Chat server 0.2.3

服务器 0.2.3 增加轮换令牌续期、按用户索引的连接队列、限流、预密钥消费墓碑，以及首次 ACK 满七天后删除已送达密文。保留现有账号、联系人、尚未送达的密文、HTTPS 与 TURN 配置。首次启动自动执行 V8–V10 数据库迁移；已有已送达消息从本次迁移时开始计算七天，避免一升级就删除。客户端先升级服务器，再更新为本轮版本；新预密钥消费与续期行为不应与旧客户端/旧服务端混搭使用。

如使用 v0.5.9 发布包，请从同一发布版本取得 `Chat-Ubuntu-Deploy.tar.gz` 与 `SHA256.txt`；也可按 [Ubuntu 部署说明](README.md)从源码构建。不要使用旧版 0.2.1 部署包。以下以 Windows PowerShell 和示例服务器地址演示上传；将本地目录、SSH 私钥和服务器 IP 换成自己的值：

```powershell
$bundle = 'C:\path\to\Chat-v0.5.9'
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

只有旧服务恢复且本机受保护接口返回 401 后，才运行新包中的安装脚本。不要传 `--origin`，脚本会保留已有 `/etc/chat/chat.env`；已有 HTTPS 反向代理和 TURN 服务不需要改动。

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

此版本包含数据库迁移，回滚旧 JAR 时必须同时恢复升级前数据库，否则旧版本不了解预密钥消费墓碑，可能造成协议错误。恢复旧数据库会丢弃备份之后的新消息和账号变更。若曾修改服务文件或环境配置，可使用对应备份恢复，但应核对并保留当前有效的 HTTPS 与 TURN 设置。

本版客户端会迁移本地加密存储，不能直接降级旧客户端；服务器数据备份不能恢复客户端私钥或聊天历史。不要卸载正式 Android 应用，使用同签名 APK 覆盖更新。
