#!/usr/bin/env bash
set -euo pipefail
umask 022

if [[ ${EUID} -ne 0 || $# -ne 1 ]]; then
  echo '用法: sudo bash publish-updates.sh /path/to/server-updates' >&2
  exit 1
fi

# The source is a flat directory containing manifest.json and the packages it lists.
# Components may have different versions. Earlier version directories are retained.
python3 - "$1" "${CHAT_UPDATES_DIRECTORY:-/opt/chat/updates}" <<'PY'
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
from pathlib import Path

source_argument = Path(sys.argv[1])
if source_argument.is_symlink():
    raise SystemExit('发布中止：server-updates 不能是符号链接')
source = source_argument.resolve(strict=True)
root = Path(sys.argv[2]).absolute()
abis = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
platforms = {'windows-x64': 'Chat-Client-Windows-x64.zip'}
for abi in abis:
    for channel in ('debug', 'release'):
        platforms[f'android-{abi}-{channel}'] = f'Chat-Android-{abi}-{channel}.apk'

def refuse(message):
    raise SystemExit(f'发布中止：{message}')

if not source.is_dir() or source.is_symlink():
    refuse('server-updates 必须是普通目录')
if root.is_symlink() or not root.is_dir() or root.stat().st_uid != 0:
    refuse('更新目录不存在、不是目录或不是 root 所有')
releases = root / 'releases'
if releases.is_symlink() or not releases.is_dir() or releases.stat().st_uid != 0:
    refuse('releases 目录不存在或权限不安全')
manifest_path = source / 'manifest.json'
if manifest_path.is_symlink() or not manifest_path.is_file():
    refuse('缺少普通 manifest.json')
if manifest_path.stat().st_size > 128 * 1024:
    refuse('manifest.json 超过 128 KiB')
try:
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
except (OSError, ValueError) as exc:
    refuse(f'manifest.json 无法读取或解析：{exc}')
if not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1:
    refuse('schemaVersion 必须为 1')
entries = manifest.get('releases')
if not isinstance(entries, list) or not entries:
    refuse('releases 必须是非空数组')
seen = set()
for item in entries:
    if not isinstance(item, dict):
        refuse('releases 条目必须是对象')
    platform, version = item.get('platform'), item.get('version')
    if platform not in platforms or not isinstance(version, str) or not re.fullmatch(r'(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})', version):
        refuse('平台或版本不合法')
    if platform in seen:
        refuse('同一平台重复')
    seen.add(platform)
    if item.get('fileName') != platforms[platform]:
        refuse('包名与平台不匹配')
    if type(item.get('size')) is not int or item['size'] <= 0 or not isinstance(item.get('sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', item['sha256']):
        refuse('size 或 sha256 不合法')
    if platform.startswith('android-') and (type(item.get('versionCode')) is not int or item['versionCode'] <= 0):
        refuse('Android versionCode 不合法')
    if platform == 'windows-x64' and item.get('versionCode') is not None:
        refuse('Windows 不应设置 versionCode')
    if 'downloadPath' in item and item['downloadPath'] != f'/api/updates/files/{platform}':
        refuse('downloadPath 不匹配')
    if 'notes' in item and (not isinstance(item['notes'], str) or len(item['notes']) > 1000):
        refuse('notes 不合法')
versions = {item['version'] for item in entries}
new_versions = {version for version in versions if not (releases / version).exists()}
for version in versions - new_versions:
    directory = releases / version
    if directory.is_symlink() or not directory.is_dir() or directory.stat().st_uid != 0:
        refuse('已有版本目录权限不安全')

temporary = {version: Path(tempfile.mkdtemp(prefix='.publishing-', dir=releases)) for version in new_versions}
published = False
try:
    for item in entries:
        name = item['fileName']
        version = item['version']
        input_path = source / name if version in new_versions else releases / version / name
        if input_path.is_symlink() or not input_path.is_file():
            refuse(f'缺少普通包文件：{name}')
        with input_path.open('rb') as incoming:
            digest = hashlib.sha256()
            size = 0
            if version in new_versions:
                with (temporary[version] / name).open('xb') as outgoing:
                    while True:
                        chunk = incoming.read(1024 * 1024)
                        if not chunk:
                            break
                        digest.update(chunk)
                        outgoing.write(chunk)
                        size += len(chunk)
                    outgoing.flush()
                    os.fsync(outgoing.fileno())
            else:
                while True:
                    chunk = incoming.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
                    size += len(chunk)
        if size != item['size'] or digest.hexdigest() != item['sha256']:
            refuse(f'包校验失败：{name}')
        if version in new_versions:
            os.chown(temporary[version] / name, 0, 0)
            os.chmod(temporary[version] / name, 0o644)
    for version in new_versions:
        os.chown(temporary[version], 0, 0)
        os.chmod(temporary[version], 0o755)
        temporary[version].rename(releases / version)
    published = True
    fd, temp_manifest = tempfile.mkstemp(prefix='.manifest-', dir=root)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(manifest_bytes)
            output.flush()
            os.fsync(output.fileno())
        os.chown(temp_manifest, 0, 0)
        os.chmod(temp_manifest, 0o644)
        os.replace(temp_manifest, root / 'manifest.json')
    finally:
        if os.path.exists(temp_manifest):
            os.unlink(temp_manifest)
finally:
    if not published:
        for directory in temporary.values():
            if directory.exists():
                shutil.rmtree(directory)
print(f'已发布 {len(entries)} 个平台安装包，组件版本：{", ".join(sorted(versions))}')
PY
