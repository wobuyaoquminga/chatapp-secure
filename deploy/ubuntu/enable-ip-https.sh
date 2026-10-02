#!/usr/bin/env bash
set -euo pipefail
umask 077
marker='# Managed by chat enable-ip-https.sh'
ip=''
email=''
while (( $# )); do
  case "$1" in
    --email|--ip) [[ $# -ge 2 ]] || { echo '参数缺少值' >&2; exit 1; }; key=$1; value=$2; shift 2; if [[ $key == --email ]]; then email=$value; else ip=$value; fi ;;
    *) echo '用法: sudo bash enable-ip-https.sh --email you@example.com --ip 服务器公网IPv4' >&2; exit 1 ;;
  esac
done
[[ -n $ip ]] || { echo '必须明确提供 --ip 服务器公网IPv4。' >&2; exit 1; }
[[ $EUID == 0 ]] || { echo '请使用 sudo 执行。' >&2; exit 1; }
[[ $email =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || { echo '必须提供有效的 --email。' >&2; exit 1; }
source /etc/os-release
[[ ${ID:-} == ubuntu && ${VERSION_ID:-} == 24.04 ]] || { echo '仅支持 Ubuntu 24.04。' >&2; exit 1; }
python3 - "$ip" <<'PY'
import ipaddress, sys
ip = ipaddress.ip_address(sys.argv[1])
if ip.version != 4 or not ip.is_global:
    raise SystemExit('需要公网 IPv4 地址')
PY
[[ -f /etc/chat/chat.env ]] || { echo '请先运行 install.sh 安装 Chat。' >&2; exit 1; }
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y nginx python3-venv ca-certificates curl
conf=/etc/nginx/sites-available/chat-ip.conf
link=/etc/nginx/sites-enabled/chat-ip.conf
for path in "$conf" /etc/systemd/system/chat-certbot-renew.service /etc/systemd/system/chat-certbot-renew.timer /etc/letsencrypt/renewal-hooks/deploy/chat-nginx-reload; do
  if [[ -e $path || -L $path ]]; then
    [[ -f $path && ! -L $path ]] && grep -qxF "$marker" "$path" || { echo "存在非本脚本管理的文件：$path；请手动处理冲突。" >&2; exit 1; }
  fi
done
if [[ -e $link || -L $link ]]; then
  [[ -L $link && $(readlink "$link") == "$conf" ]] || { echo "站点链接冲突：$link" >&2; exit 1; }
fi
# Conservatively refuse another enabled configuration mentioning this address.
if grep -RFl --exclude=chat-ip.conf -- "$ip" /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null; then
  echo '其它启用的 nginx 配置包含目标 IP；请先人工检查冲突。' >&2; exit 1
fi
nginx -t
install -d -m 0755 /var/www/chat-acme /opt/chat-certbot
if [[ ! -x /opt/chat-certbot/bin/python ]]; then python3 -m venv /opt/chat-certbot; fi
/opt/chat-certbot/bin/python -m pip install --upgrade 'certbot>=5.4,<6'
/opt/chat-certbot/bin/certbot --version
certname="chat-ip-${ip//./-}"
renewal="/etc/letsencrypt/renewal/$certname.conf"
if [[ -f $renewal ]] && ! grep -qxF "$marker" "$conf" 2>/dev/null; then
  echo "已有同名证书配置 $renewal；请人工检查后重试。" >&2; exit 1
fi
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
write_http() {
  cat <<EOF
$marker
server {
    listen 80;
    server_name $ip;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/chat-acme;
        default_type text/plain;
        try_files \$uri =404;
    }
    location / { return 403; }
}
EOF
}
# Validate each change before reload; restore the previous site on validation failure.
apply_config() {
  local candidate=$1 had_conf=0 had_link=0
  if [[ -f $conf ]]; then cp -p "$conf" "$work/previous"; had_conf=1; fi
  [[ -L $link ]] && had_link=1
  install -m 0644 "$candidate" "$conf"
  [[ -L $link ]] || ln -s "$conf" "$link"
  if ! nginx -t; then
    if (( had_conf )); then cp -p "$work/previous" "$conf"; else rm -f "$conf"; fi
    (( had_link )) || rm -f "$link"
    echo 'nginx 配置验证失败，已恢复之前的站点文件。' >&2
    return 1
  fi
  systemctl enable --now nginx
  systemctl reload nginx
}
# Existing working HTTPS stays available on reruns; initial issuance exposes challenges only.
if [[ ! -f $conf ]]; then write_http > "$work/http"; apply_config "$work/http"; fi
/opt/chat-certbot/bin/certbot certonly --non-interactive --agree-tos \
  --email "$email" --cert-name "$certname" --ip-address "$ip" \
  --preferred-profile shortlived --webroot --webroot-path /var/www/chat-acme
certdir="/etc/letsencrypt/live/$certname"
[[ -s $certdir/fullchain.pem && -s $certdir/privkey.pem ]]
cat > "$work/https" <<EOF
$marker
limit_req_zone \$binary_remote_addr zone=chat_ip_auth:10m rate=5r/s;
server {
    listen 80;
    server_name $ip;
    location ^~ /.well-known/acme-challenge/ {
        root /var/www/chat-acme;
        default_type text/plain;
        try_files \$uri =404;
    }
    location / { return 301 https://$ip\$request_uri; }
}
server {
    listen 443 ssl;
    server_name $ip;
    ssl_certificate $certdir/fullchain.pem;
    ssl_certificate_key $certdir/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 512k;
    add_header Strict-Transport-Security "max-age=86400" always;
    location ^~ /api/auth/ {
        client_max_body_size 8k;
        limit_req zone=chat_ip_auth burst=30 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:8082;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
    location / {
        proxy_pass http://127.0.0.1:8082;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
EOF
cp -p "$conf" "$work/nginx-before-https"
cp -p /etc/chat/chat.env "$work/chat.env"
renew_service=/etc/systemd/system/chat-certbot-renew.service
renew_timer=/etc/systemd/system/chat-certbot-renew.timer
reload_hook=/etc/letsencrypt/renewal-hooks/deploy/chat-nginx-reload
had_renew_service=0
had_renew_timer=0
had_reload_hook=0
timer_was_enabled=0
timer_was_active=0
if [[ -f $renew_service ]]; then cp -p "$renew_service" "$work/renew.service"; had_renew_service=1; fi
if [[ -f $renew_timer ]]; then cp -p "$renew_timer" "$work/renew.timer"; had_renew_timer=1; fi
if [[ -f $reload_hook ]]; then cp -p "$reload_hook" "$work/reload-hook"; had_reload_hook=1; fi
if systemctl is-enabled --quiet chat-certbot-renew.timer; then timer_was_enabled=1; fi
if systemctl is-active --quiet chat-certbot-renew.timer; then timer_was_active=1; fi
rollback_https() {
  local status=$?
  trap - EXIT
  set +e
  if (( status != 0 )); then
    cp -p "$work/nginx-before-https" "$conf"
    cp -p "$work/chat.env" /etc/chat/chat.env
    if nginx -t; then systemctl reload nginx || true; fi
    systemctl restart chat.service || true
    systemctl disable --now chat-certbot-renew.timer || true
    if (( had_renew_service )); then cp -p "$work/renew.service" "$renew_service"; else rm -f "$renew_service"; fi
    if (( had_renew_timer )); then cp -p "$work/renew.timer" "$renew_timer"; else rm -f "$renew_timer"; fi
    if (( had_reload_hook )); then cp -p "$work/reload-hook" "$reload_hook"; else rm -f "$reload_hook"; fi
    systemctl daemon-reload || true
    if (( timer_was_enabled )); then systemctl enable chat-certbot-renew.timer || true; fi
    if (( timer_was_active )); then systemctl start chat-certbot-renew.timer || true; fi
    echo 'HTTPS 配置未通过检查，已恢复之前的 nginx、应用来源和证书续期设置。' >&2
  fi
  rm -rf -- "$work"
  exit "$status"
}
trap rollback_https EXIT
apply_config "$work/https"
install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
cat > "$reload_hook" <<'EOF'
#!/usr/bin/env bash
# Managed by chat enable-ip-https.sh
set -euo pipefail
/usr/sbin/nginx -t
/usr/bin/systemctl reload nginx
EOF
chmod 0755 "$reload_hook"
cat > "$renew_service" <<EOF
$marker
[Unit]
Description=Renew Chat short-lived IP TLS certificate
Wants=network-online.target
After=network-online.target
[Service]
Type=oneshot
ExecStart=/opt/chat-certbot/bin/certbot renew --non-interactive --cert-name $certname
EOF
cat > "$renew_timer" <<EOF
$marker
[Unit]
Description=Check Chat IP certificate renewal twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=30m
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now chat-certbot-renew.timer
# Set only the origin entry; never print or source application secrets.
if grep -q '^CHAT_ALLOWED_ORIGINS=' /etc/chat/chat.env; then
  sed -i "s|^CHAT_ALLOWED_ORIGINS=.*|CHAT_ALLOWED_ORIGINS=https://$ip|" /etc/chat/chat.env
else
  printf '\nCHAT_ALLOWED_ORIGINS=https://%s\n' "$ip" >> /etc/chat/chat.env
fi
chmod 0600 /etc/chat/chat.env
systemctl restart chat.service
ready=0
for attempt in {1..60}; do
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 --noproxy '*' --resolve "$ip:443:127.0.0.1" "https://$ip/api/contacts" || true)
  if [[ $status == 401 ]]; then ready=1; break; fi
  sleep 2
done
if (( ! ready )); then
  echo 'HTTPS 本机可信连接/应用检查未通过；请检查 nginx 和 chat 日志。' >&2
  exit 1
fi
echo "配置完成：https://$ip；HTTPS 本机检查通过（401），已启用每天两次的证书续期检查。"
echo "请验证公网浏览器访问；续期测试：sudo /opt/chat-certbot/bin/certbot renew --cert-name $certname --dry-run"
