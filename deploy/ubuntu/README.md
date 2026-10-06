# Ubuntu 24.04 / Java 17 部署

## 从本机源码构建

源码仓库不包含服务器 JAR。在包含服务器 0.2.7 源码的工作树根目录安装 Java 17 后执行：

```sh
cd server
bash mvnw -B -ntp clean verify
cd ..
cp server/target/chat-server-0.2.7.jar deploy/ubuntu/chat-server.jar
```

将 `deploy/ubuntu` 整个目录上传到服务器，再执行以下安装步骤。使用发布包中的 `Chat-Ubuntu-Deploy.tar.gz` 时无需重复构建；先按[升级说明](UPGRADE.md)校验同目录 `SHA256.txt`。

如有可绑定的公网 IPv4 地址但暂无域名，安装 Chat 后可运行 `sudo bash enable-ip-https.sh --email admin@example.com --ip 203.0.113.10` 启用可信 IP HTTPS；将示例邮箱和文档专用 IP 换成自己的真实值，详见 [IP HTTPS 说明](IP-HTTPS说明.md)。无需为此购买域名。脚本需要安全组放行 80、443，申请失败须先排查输出，不能当作已上线。

语音和视频通话需要服务器的临时信令接口。只使用默认 STUN 时，严格 NAT 或不同运营商网络可能无法直连；生产环境按根目录[通话说明](../../语音与视频通话.md)运行 `enable-turn.sh`，并在云安全组放行 TCP/UDP 3478、UDP 49160–49200。TURN 不需要开放 Chat 的 8082 端口。重复运行 `enable-turn.sh` 会保留现有共享密钥，避免正在使用的临时凭据提前失效。现有服务器升级到 0.2.7 见[升级说明](UPGRADE.md)；本次升级不要求重配 TURN。

验证范围和已完成的检查见根目录 `VALIDATION.md`。首次在自己的服务器安装后，还需自行核对证书、公网访问和两个客户端的实际通信。

本包仅包含新服务器 jar 和安装配置，不含旧数据库或运行日志。安装不需要域名，不开启公网 HTTP。服务器仅监听 127.0.0.1:8082，由 systemd 自动启动、失败重启。H2 数据保存在 /var/lib/chat/data；首次安装生成 JWT 密钥及数据库密码，存放于 root 专用 /etc/chat/chat.env，脚本不输出秘密。重复安装保留现有密钥和数据库。

## 首次安装

将整个目录上传到 Ubuntu 24.04 云服务器（例如 /home/ecs-user/ubuntu-deploy），执行：

```sh
cd ~/ubuntu-deploy
bash -n install.sh
sudo bash install.sh
sudo systemctl status chat --no-pager
curl -o /dev/null -s -w '%{http_code}\n' http://127.0.0.1:8082/api/contacts
```

最后应输出 401：未登录访问受保护接口，说明服务可响应。本应用没有专用 /health 接口。首次安装会联网安装 Java17、openssl、curl，需要 Ubuntu 软件源可访问。此命令只检查服务器本机的 HTTP 服务，不代表公网 HTTPS 已可用。

## 无域名：先用电脑通过 SSH 隧道测试

在 Windows PowerShell 打开以下连接并保持窗口运行：

```powershell
ssh -i "$env:USERPROFILE\.ssh\id_ed25519" -N -L 18082:127.0.0.1:8082 ecs-user@203.0.113.10
```

`203.0.113.10` 是文档专用示例地址，执行前须替换为服务器实际公网 IP。

将桌面客户端服务器地址设置为 `http://127.0.0.1:18082`。新安装的允许来源包含 localhost/127.0.0.1 的 18082、8082 端口。若此前安装已存在 chat.env，脚本会保留原配置；请用 `sudoedit /etc/chat/chat.env` 更新 CHAT_ALLOWED_ORIGINS，包含 `http://127.0.0.1:18082,http://localhost:18082`，然后 `sudo systemctl restart chat`。该隧道只适用于这台电脑；手机公网使用需另行配置可信 HTTPS。云安全组仅需当前测试电脑访问 SSH 22；无需放行8082。

## 后续域名与 HTTPS

取得域名，将 DNS 指向服务器公网 IP，再配置可信 CA 证书。确认 HTTPS 就绪后编辑 /etc/chat/chat.env 中 CHAT_ALLOWED_ORIGINS 为 `https://chat.example.com` 并重启 chat；或更新时运行 `sudo bash install.sh --origin https://chat.example.com`。`chat.example.com` 是示例域名，须替换为实际域名。该选项只设置来源，不生成证书或启用代理。

nginx-https.conf.example 提供 HTTPS 和 WebSocket /ws 反向代理模板。需要自行安装 nginx、替换域名及证书路径，先取得可信证书，再将配置链接到 sites-enabled，执行 `sudo nginx -t`，通过后重新加载 nginx，并在安全组放行443（使用HTTP证书验证或重定向时还需80）。不要把没有证书的模板直接启用。本包不生成自签名证书。

## 维护与更新

日志：`sudo journalctl -u chat -n 80 --no-pager`。重启：`sudo systemctl restart chat`。更新：替换本包 chat-server.jar 后再次运行安装脚本。脚本会短暂停服替换 jar；默认 H2 路径会先保存静止数据快照，启动检查失败时同时恢复数据库、原 jar、服务文件和 chat.env。自定义数据库不提供自动数据回滚，须按升级指南手动恢复后启动原服务。0.2.6 升级至 0.2.7 没有新增数据库迁移；从更早版本升级会执行尚未应用的迁移（包括 V11 附件元数据）。安装前仍需停服备份 /var/lib/chat/data 和 /etc/chat/chat.env 到受保护位置，并在备份后恢复旧服务再运行安装脚本，以便安装检查失败时自动恢复原服务。H2 只允许单个服务进程使用同一数据文件，不可复制到另一台服务器后并行写入。不要将 chat.env、数据库或私钥上传到公开位置。

首次送达 ACK 满七天的密文每小时分批删除；未送达消息保留到原账号清理规则触发，本机历史不受影响。磁盘空间低于 512 MiB 或 10% 时写入不含消息正文的告警日志。服务器为单实例架构。

prod 模式只信任来自回环地址且由本机 nginx 覆盖的 X-Real-IP，以便按真实来源限制登录尝试；自定义代理必须覆盖该头，不能直接转发用户提供的值。nginx 新配置提供登录限速和请求体限制，旧 HTTPS 配置不会因替换 JAR 自动更新。不要为了更新限流重新申请现有证书；可参考本包 nginx 模板将相应指令合并到现有配置，用 sudo nginx -t 检查后 reload。
