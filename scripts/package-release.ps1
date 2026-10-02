param([string]$OutputRoot, [string]$WorkRoot)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$manifest = Get-Content -LiteralPath (Join-Path $repo 'release.json') -Raw | ConvertFrom-Json
& node (Join-Path $PSScriptRoot 'check-versions.cjs')
if ($LASTEXITCODE) { throw 'Version check failed' }
if (!$OutputRoot) { $OutputRoot = Split-Path $repo -Parent }
if (!$WorkRoot) { $WorkRoot = Join-Path (Split-Path $OutputRoot -Parent) 'work' }
$release = Join-Path $OutputRoot ('Chat-v' + $manifest.bundle)
$stage = Join-Path $WorkRoot ('package-stage-v' + $manifest.bundle)
$windows = Join-Path $repo 'release/Chat-win32-x64'
$jar = Join-Path $repo ('server/target/chat-server-' + $manifest.server + '.jar')
$apk = Join-Path $repo 'android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
foreach ($file in @($jar,$apk,(Join-Path $windows 'Chat.exe'),(Join-Path $windows 'LICENSE-Chat.txt'),(Join-Path $windows 'NOTICE-Chat.md'))) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Build artifact missing: $file" }
}
# Never delete or overwrite a release. Build into a new directory after checks.
if (Test-Path -LiteralPath $release) { throw "Release already exists: $release" }
New-Item -ItemType Directory -Path $release -Force | Out-Null
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($windows,(Join-Path $release 'Chat-Client-Windows-x64.zip'),[IO.Compression.CompressionLevel]::Optimal,$true)
Copy-Item -LiteralPath $apk -Destination (Join-Path $release 'Chat-Android-arm64-v8a-debug.apk')
$ubuntu = Join-Path $stage 'ubuntu-deploy'
New-Item -ItemType Directory -Path $ubuntu -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $repo 'deploy/ubuntu') -File | Copy-Item -Destination $ubuntu
Copy-Item -LiteralPath $jar -Destination (Join-Path $ubuntu 'chat-server.jar')
Copy-Item -LiteralPath (Join-Path $repo '语音与视频通话.md') -Destination $ubuntu
$readme = Join-Path $ubuntu 'README.md'
$text = [IO.File]::ReadAllText($readme).Replace('../../语音与视频通话.md','语音与视频通话.md')
[IO.File]::WriteAllText($readme,$text,[Text.UTF8Encoding]::new($false))
& tar -czf (Join-Path $release 'Chat-Ubuntu-Deploy.tar.gz') -C $stage ubuntu-deploy
if ($LASTEXITCODE) { throw 'Deployment archive failed' }
# git enumerates source files and new nonignored files; no credentials/build directories.
$files = & git -C $repo -c core.quotepath=false ls-files --cached --others --exclude-standard
if ($LASTEXITCODE) { throw 'Source enumeration failed' }
$zip = [IO.Compression.ZipFile]::Open((Join-Path $release 'Chat-Source.zip'),[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($relative in $files | Sort-Object -Unique) {
        $absolute = Join-Path $repo $relative
        if (!(Test-Path -LiteralPath $absolute -PathType Leaf)) { continue }
        if ($relative -match '(^|/)(node_modules|target|build|release|\.git)/|\.(keystore|jks|pem|p12|pfx|vault|mv\.db)$|(^|/)local\.properties$') { throw "Private/generated source path refused: $relative" }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$absolute,('Chat-Source/'+$relative),[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose() }
Copy-Item -LiteralPath (Join-Path $repo 'VALIDATION.md') -Destination (Join-Path $release '测试报告.md')
Copy-Item -LiteralPath (Join-Path $repo 'deploy/ubuntu/UPGRADE.md') -Destination (Join-Path $release '服务器升级说明.md')
Copy-Item -LiteralPath (Join-Path $repo 'release.json') -Destination $release
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination (Join-Path $release 'LICENSE-Chat.txt')
Copy-Item -LiteralPath (Join-Path $repo 'NOTICE.md') -Destination (Join-Path $release 'NOTICE-Chat.md')
$guide = @"
Chat v$($manifest.bundle)
Windows $($manifest.windows) / Android $($manifest.android) (code $($manifest.androidCode)) / server $($manifest.server)

先升级服务器，再更新客户端。具体命令见同目录的服务器升级说明.md。
Chat-Client-Windows-x64.zip：完整解压后运行 Chat-win32-x64/Chat.exe，保留同一 Windows 用户原本的应用数据。
Chat-Android-arm64-v8a-debug.apk：使用原签名覆盖更新；不要卸载或清空原应用数据。其他 ABI 可从源码构建。
Chat-Ubuntu-Deploy.tar.gz：上传并校验后用于 Ubuntu 24.04 部署，保留现有 HTTPS/TURN 配置。
Chat-Source.zip：完整项目源码、第三方对应源码、构建和验证脚本；不包含用户数据库或签名密钥。
SHA256.txt：上述安装包与源码包的 SHA-256。

数据库自动执行 V8–V10 迁移；回滚旧服务器必须同时恢复升级前的静止数据库和 JAR。
客户端本地加密存储会迁移，迁移后不能直接降级。Windows 不要拆分 vault 与 history；安卓不要还原旧 migrated 文件。
已送达服务器密文从首次 ACK 满七天后删除，本机历史保留；未送达消息仍按账号规则保留。
各平台测试覆盖与未验证的功能见测试报告.md。下载与更新信息见 GitHub 对应版本发布页。
本项目为开发原型。文字和位置使用 libsignal；通话媒体用 WebRTC DTLS-SRTP，信令尚未绑定 Signal 安全码。
"@
[IO.File]::WriteAllText((Join-Path $release '成品说明.txt'),$guide,[Text.UTF8Encoding]::new($false))
$hashes = Get-ChildItem -LiteralPath $release -File | Where-Object Extension -in @('.zip','.apk','.gz') | Sort-Object Name | ForEach-Object {
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
}
[IO.File]::WriteAllLines((Join-Path $release 'SHA256.txt'),$hashes,[Text.UTF8Encoding]::new($false))
Write-Output "Packaged: $release"
