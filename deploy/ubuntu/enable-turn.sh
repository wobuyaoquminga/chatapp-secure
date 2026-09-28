#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ ${EUID} -ne 0 ]]; then
  echo '请使用 sudo bash enable-turn.sh --public-ip 服务器公网IPv4' >&2
  exit 1
fi

public_ip=''
if [[ $# -eq 2 && $1 == --public-ip ]]; then
  public_ip=$2
fi
if [[ ! $public_ip =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo '用法: sudo bash enable-turn.sh --public-ip 203.0.113.10' >&2
  exit 1
fi
IFS=. read -r a b c d <<<"$public_ip"
for octet in "$a" "$b" "$c" "$d"; do
  if (( 10#$octet < 0 || 10#$octet > 255 )); then
    echo '公网 IPv4 格式无效。' >&2
    exit 1
  fi
done
[[ -f /etc/chat/chat.env ]] || { echo '请先安装 Chat 服务器。' >&2; exit 1; }

source /etc/os-release
if [[ ${ID:-} != ubuntu || ${VERSION_ID:-} != 24.04 ]]; then
  echo '本脚本仅面向 Ubuntu 24.04。' >&2
  exit 1
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y coturn openssl

secret=$(openssl rand -base64 32 | tr -d '\n')
private_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{ for (i=1; i<=NF; i++) if ($i == "src") { print $(i+1); exit } }')
external_ip=$public_ip
if [[ -n ${private_ip:-} && $private_ip != "$public_ip" ]]; then
  external_ip="$public_ip/$private_ip"
fi

cat > /etc/turnserver.conf <<EOF
listening-port=3478
fingerprint
use-auth-secret
static-auth-secret=$secret
realm=chat
external-ip=$external_ip
min-port=49160
max-port=49200
stale-nonce=600
user-quota=12
total-quota=1200
no-cli
no-multicast-peers
no-loopback-peers
EOF
getent group turnserver >/dev/null || { echo 'coturn 安装后缺少 turnserver 组。' >&2; exit 1; }
chown root:turnserver /etc/turnserver.conf
chmod 0640 /etc/turnserver.conf
sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn

tmp=$(mktemp)
grep -Ev '^CHAT_(STUN_URLS|TURN_URLS|TURN_SHARED_SECRET|TURN_TTL_SECONDS)=' /etc/chat/chat.env > "$tmp"
cat >> "$tmp" <<EOF
CHAT_STUN_URLS=stun:$public_ip:3478
CHAT_TURN_URLS='turn:$public_ip:3478?transport=udp,turn:$public_ip:3478?transport=tcp'
CHAT_TURN_SHARED_SECRET='$secret'
CHAT_TURN_TTL_SECONDS=600
EOF
install -o root -g root -m 0600 "$tmp" /etc/chat/chat.env
rm -f "$tmp"
unset secret

systemctl enable --now coturn
systemctl restart coturn chat

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  ufw allow 3478/tcp
  ufw allow 3478/udp
  ufw allow 49160:49200/udp
fi

systemctl is-active --quiet coturn
systemctl is-active --quiet chat
echo 'TURN 已启用。还必须在云服务器安全组放行 TCP/UDP 3478 和 UDP 49160-49200。'
echo '完成后用两台处于不同网络的设备实际拨打，确认中继连通。'
