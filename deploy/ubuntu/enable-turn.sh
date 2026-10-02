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
  echo '用法: sudo bash enable-turn.sh --public-ip 服务器公网IPv4' >&2
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

# Capture files and service states before package installation can start/enable coturn.
work=$(mktemp -d /run/chat-turn.XXXXXX)
committed=0
backups_ready=0
coturn_enabled=$(systemctl is-enabled coturn.service 2>/dev/null || true)
chat_enabled=$(systemctl is-enabled chat.service 2>/dev/null || true)
coturn_active=0
chat_active=0
if systemctl is-active --quiet coturn.service; then coturn_active=1; fi
if systemctl is-active --quiet chat.service; then chat_active=1; fi
backup_file() {
  local path=$1 name=$2
  [[ ! -L $path ]] || { echo "配置文件不能是符号链接：$path" >&2; return 1; }
  if [[ -e $path ]]; then cp -p -- "$path" "$work/$name"; fi
}
restore_file() {
  local path=$1 name=$2
  if [[ -f $work/$name ]]; then cp -p -- "$work/$name" "$path"; else rm -f -- "$path"; fi
}
restore_service() {
  local name=$1 enabled=$2 active=$3
  case "$enabled" in
    enabled) systemctl enable "$name" ;;
    enabled-runtime) systemctl enable --runtime "$name" ;;
    masked) systemctl mask "$name" ;;
    masked-runtime) systemctl mask --runtime "$name" ;;
    *) systemctl disable "$name" ;;
  esac
  if (( active )); then systemctl restart "$name"; else systemctl stop "$name"; fi
}
cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if (( backups_ready && ! committed )); then
    # Stop new daemons before restoring the old credentials/configuration.
    systemctl stop coturn.service
    restore_file /etc/turnserver.conf turnserver.conf
    restore_file /etc/chat/chat.env chat.env
    restore_file /etc/default/coturn coturn.default
    restore_service coturn.service "$coturn_enabled" "$coturn_active"
    restore_service chat.service "$chat_enabled" "$chat_active"
    echo 'TURN 设置未完成，已尝试恢复之前的配置和服务状态；请检查上方恢复命令结果。' >&2
  fi
  unset secret secret_env
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
backup_file /etc/turnserver.conf turnserver.conf
backup_file /etc/chat/chat.env chat.env
backup_file /etc/default/coturn coturn.default
backups_ready=1
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y coturn openssl curl
getent group turnserver >/dev/null || { echo 'coturn 安装后缺少 turnserver 组。' >&2; exit 1; }

# Keep the existing shared secret on repeat runs so issued TURN credentials
# remain valid until their normal expiry.
secret=''
if [[ -f /etc/turnserver.conf ]]; then
  secret=$(awk -F= '$1 == "static-auth-secret" { print substr($0, index($0, "=") + 1); exit }' /etc/turnserver.conf)
fi
if [[ -z $secret ]]; then
  secret_env=$(awk -F= '$1 == "CHAT_TURN_SHARED_SECRET" { print substr($0, index($0, "=") + 1); exit }' /etc/chat/chat.env)
  secret_env=${secret_env#\'}; secret_env=${secret_env%\'}
  secret_env=${secret_env#\"}; secret_env=${secret_env%\"}
  secret=$secret_env
fi
if [[ -z $secret ]]; then secret=$(openssl rand -base64 32 | tr -d '\n'); fi
if [[ ! $secret =~ ^[A-Za-z0-9+/=]+$ ]]; then
  echo '现有 TURN 密钥格式无效，请先检查 /etc/turnserver.conf。' >&2
  exit 1
fi
private_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{ for (i=1; i<=NF; i++) if ($i == "src") { print $(i+1); exit } }')
external_ip=$public_ip
if [[ -n ${private_ip:-} && $private_ip != "$public_ip" ]]; then
  external_ip="$public_ip/$private_ip"
fi

cat > "$work/turnserver.new" <<EOF
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
# 41 UDP relay ports provide at most 41 ordinary allocations. Leave one port
# spare and cap allocations at 40; calls may need multiple allocations/devices,
# so this is not a promise of 40 simultaneous calls.
total-quota=40
no-cli
no-multicast-peers
no-loopback-peers
EOF
install -o root -g turnserver -m 0640 "$work/turnserver.new" /etc/turnserver.conf
if grep -q '^#\?TURNSERVER_ENABLED=' /etc/default/coturn; then
  sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
else
  printf '\nTURNSERVER_ENABLED=1\n' >> /etc/default/coturn
fi

tmp="$work/chat.env.new"
# grep exits 1 for an empty result, which is still a valid replacement env file.
grep -Ev '^CHAT_(STUN_URLS|TURN_URLS|TURN_SHARED_SECRET|TURN_TTL_SECONDS)=' /etc/chat/chat.env > "$tmp" || [[ $? == 1 ]]
cat >> "$tmp" <<EOF
CHAT_STUN_URLS=stun:$public_ip:3478
CHAT_TURN_URLS='turn:$public_ip:3478?transport=udp,turn:$public_ip:3478?transport=tcp'
CHAT_TURN_SHARED_SECRET='$secret'
CHAT_TURN_TTL_SECONDS=600
EOF
install -o root -g root -m 0600 "$tmp" /etc/chat/chat.env
unset secret secret_env

systemctl enable --now coturn
systemctl restart coturn chat

systemctl is-active --quiet coturn
systemctl is-active --quiet chat
ready=0
for attempt in {1..60}; do
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 3 --noproxy '*' http://127.0.0.1:8082/api/contacts || true)
  if [[ $status == 401 ]]; then ready=1; break; fi
  sleep 2
done
if (( ! ready )); then
  echo 'Chat 本机就绪检查未通过，TURN 和应用配置将恢复。' >&2
  exit 1
fi
systemctl is-active --quiet coturn
systemctl is-active --quiet chat

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  ufw allow 3478/tcp
  ufw allow 3478/udp
  ufw allow 49160:49200/udp
fi

committed=1
echo 'TURN 已启用。还必须在云服务器安全组放行 TCP/UDP 3478 和 UDP 49160-49200。'
echo '完成后用两台处于不同网络的设备实际拨打，确认中继连通。'
