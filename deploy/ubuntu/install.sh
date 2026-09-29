#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ ${EUID} -ne 0 ]]; then echo '请使用 sudo bash install.sh' >&2; exit 1; fi
origin=''
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || $1 != --origin || ! $2 =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]]; then
    echo '用法: sudo bash install.sh [--origin https://example.com]' >&2; exit 1
  fi
  origin=$2
fi
source /etc/os-release
if [[ ${ID:-} != ubuntu || ${VERSION_ID:-} != 24.04 ]]; then
  echo '本安装包仅面向 Ubuntu 24.04。' >&2; exit 1
fi
base=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ -f "$base/chat-server.jar" ]] || { echo '缺少 chat-server.jar' >&2; exit 1; }
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jre-headless openssl curl
if ! id chat >/dev/null 2>&1; then useradd --system --home-dir /var/lib/chat --shell /usr/sbin/nologin chat; fi
install -d -m 0755 /opt/chat
install -d -o chat -g chat -m 0700 /var/lib/chat /var/lib/chat/data
install -d -o root -g root -m 0700 /etc/chat
if [[ ! -f /etc/chat/chat.env ]]; then
  jwt=$(openssl rand -hex 32)
  dbpass=$(openssl rand -hex 32)
  cat > /etc/chat/chat.env <<EOF
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=127.0.0.1
PORT=8082
DB_URL='jdbc:h2:file:/var/lib/chat/data/secure-chat;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0'
DB_USER=sa
DB_PASSWORD=$dbpass
JWT_SECRET=$jwt
CHAT_ALLOWED_ORIGINS=http://localhost:8082,http://127.0.0.1:8082,http://127.0.0.1:18082,http://localhost:18082
EOF
  unset jwt dbpass
fi
backup=$(mktemp -d /opt/chat/.install-backup.XXXXXX)
had_jar=0
had_service=0
had_env=0
was_active=0
if [[ -f /opt/chat/chat-server.jar ]]; then
  cp -p /opt/chat/chat-server.jar "$backup/chat-server.jar"
  had_jar=1
fi
if [[ -f /etc/systemd/system/chat.service ]]; then
  cp -p /etc/systemd/system/chat.service "$backup/chat.service"
  had_service=1
fi
if [[ -f /etc/chat/chat.env ]]; then
  cp -p /etc/chat/chat.env "$backup/chat.env"
  had_env=1
fi
if systemctl is-active --quiet chat.service; then was_active=1; fi
rollback() {
  local status=$?
  trap - EXIT
  set +e
  if (( status != 0 )); then
    echo '安装未通过检查，正在恢复原有服务。' >&2
    systemctl stop chat.service || true
    if (( had_jar )); then cp -p "$backup/chat-server.jar" /opt/chat/chat-server.jar; else rm -f /opt/chat/chat-server.jar; fi
    if (( had_service )); then cp -p "$backup/chat.service" /etc/systemd/system/chat.service; else rm -f /etc/systemd/system/chat.service; fi
    if (( had_env )); then cp -p "$backup/chat.env" /etc/chat/chat.env; fi
    systemctl daemon-reload || true
    if (( was_active )); then systemctl restart chat.service || true; fi
  fi
  rm -rf -- "$backup"
  exit "$status"
}
trap rollback EXIT
if [[ -n "$origin" ]]; then
  sed -i "s|^CHAT_ALLOWED_ORIGINS=.*|CHAT_ALLOWED_ORIGINS=$origin|" /etc/chat/chat.env
fi
chown root:root /etc/chat/chat.env
chmod 0600 /etc/chat/chat.env
# Replace the binary only after the old process has stopped. Keep a local copy
# until the HTTP readiness check has passed.
systemctl stop chat.service 2>/dev/null || true
if systemctl is-active --quiet chat.service; then
  echo '旧服务未能停止，安装已中止。' >&2
  exit 1
fi
install -o root -g root -m 0644 "$base/chat-server.jar" "$backup/new-chat-server.jar"
mv -f "$backup/new-chat-server.jar" /opt/chat/chat-server.jar
cat > /etc/systemd/system/chat.service <<'EOF'
[Unit]
Description=Secure Chat Java Server
After=network.target

[Service]
Type=simple
User=chat
Group=chat
WorkingDirectory=/var/lib/chat
EnvironmentFile=/etc/chat/chat.env
ExecStart=/usr/bin/java -Xms128m -Xmx768m -jar /opt/chat/chat-server.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=45
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/chat

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now chat.service
# A protected endpoint returns 401 when the web application is ready.
for attempt in {1..60}; do
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 http://127.0.0.1:8082/api/contacts || true)
  if [[ $status == 401 ]]; then
    echo '安装完成：服务已启动，回环访问检查通过（401）。'
    echo '未开启公网 HTTP/HTTPS；请按 README 使用 SSH 隧道测试或配置可信 HTTPS。'
    exit 0
  fi
  sleep 2
done
echo '服务未在等待时间内通过检查，请执行 sudo journalctl -u chat -n 80 --no-pager 排查。' >&2
exit 1
