# 公网 IP 的可信 HTTPS

适用环境：Ubuntu 24.04，Chat 已通过同目录 `install.sh` 安装，监听 `127.0.0.1:8082`。使用服务器实际公网 IPv4 申请 Let’s Encrypt IP 证书，无需域名；IP 证书有效期约 6 天，自动续期不能关闭。下文 `203.0.113.10` 是文档专用示例地址，运行命令前必须替换成服务器实际公网 IP。

## 使用

将整个部署目录上传到服务器，先安装 Chat，再执行。示例邮箱和 IP 均须替换为真实值：

```bash
sudo bash install.sh
sudo bash enable-ip-https.sh --email admin@example.com --ip 203.0.113.10
```

执行前，在云服务商安全组和服务器防火墙允许公网入站 TCP 80、443。8082 应保持仅回环监听，无需向公网开放。公网 IP 必须实际指向这台服务器；HTTP-01 验证要求公网能访问 80 端口。脚本不会修改安全组或防火墙。

首次签发前，脚本只给此 IP 的 80 端口站点开放 `/.well-known/acme-challenge/`，其它请求返回 403。成功取得可信证书后启用 HTTPS、WebSocket 反向代理，并将普通 HTTP 请求重定向到 HTTPS；ACME 路径仍保留供续期使用。申请失败时首次安装保持上述 80 端口受限站点，可处理日志中的问题后重试。

脚本使用独立 Python 虚拟环境 `/opt/chat-certbot`，安装 `certbot>=5.4,<6`，打印实际版本，不使用 Snap，不使用不支持 IP 证书的 `--nginx` 插件。申请参数采用 `--ip-address`、`--preferred-profile shortlived` 和 webroot。配置文件为 `/etc/nginx/sites-available/chat-ip.conf`；保留现有 default 和其它站点，不覆盖非本脚本管理的同名文件。若启用的其它配置包含目标 IP，脚本保守退出，需人工检查冲突；复杂自定义 nginx include 或通配站点也应由管理员检查。

应用配置在 `/etc/chat/chat.env`。脚本将 `CHAT_ALLOWED_ORIGINS` 设置为该 IP 的 HTTPS 地址，并重启 `chat.service`；已有其它前端来源如需保留，应在此变量中按逗号分隔补充。数据库和密钥保持原样。

## 验证与续期

```bash
sudo nginx -t
sudo systemctl status chat.service nginx chat-certbot-renew.timer --no-pager
sudo systemctl list-timers chat-certbot-renew.timer
sudo /opt/chat-certbot/bin/certbot certificates
sudo /opt/chat-certbot/bin/certbot renew --cert-name chat-ip-203-0-113-10 --dry-run
sudo journalctl -u chat-certbot-renew.service -n 80 --no-pager
```

证书名称中的数字应与实际 IP 对应；更换 IP 后也要更新该名称。浏览器打开实际服务器的 HTTPS 地址，确认无证书警告，并检查登录和实时消息。切勿通过忽略证书错误来验收。

专用计时器每天 00:00 和 12:00（服务器本地时区）检查续期，附加最多 30 分钟随机延迟；遗漏的任务在服务器恢复后补执行。续期成功后 deploy hook 先执行 `nginx -t`，通过后 reload。证书无需重启 Java 服务。请监控续期服务失败和证书到期时间；不能因“已设自动续期”而忽略持续失败。

以上是部署操作说明，未在目标服务器执行或验收。本脚本不会自动运行测试签发；`--dry-run` 可验证续期通路，但仍应检查真实浏览器证书、服务和消息功能。

官方依据：[Let’s Encrypt：IP 证书与 Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot/)。
