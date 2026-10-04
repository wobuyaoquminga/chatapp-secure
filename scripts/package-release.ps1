param([string]$OutputRoot, [string]$WorkRoot, [string]$PreviousDebugApk)
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
$releaseApk = Join-Path $repo 'android/app/build/outputs/apk/release/app-arm64-v8a-release.apk'
foreach ($file in @($jar,$apk,$releaseApk,(Join-Path $windows 'Chat.exe'),(Join-Path $windows 'LICENSE-Chat.txt'),(Join-Path $windows 'NOTICE-Chat.md'))) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Build artifact missing: $file" }
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (!$sdk -or !$env:JAVA_HOME) { throw 'Set ANDROID_HOME and JAVA_HOME to verify APK signatures before packaging.' }
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
function Get-ApkMetadata([string]$File) {
    $variant = if ([IO.Path]::GetFileName($File) -match 'release\.apk$') { 'Release' } else { 'Debug' }
    $info = & (Join-Path $repo 'android/install-usb.ps1') -ValidateOnly -Variant $variant -ApkPath $File
    if (!$info -or !$info.Certificate) { throw 'APK metadata or signature check failed' }
    [pscustomobject]@{ package=$info.Package; versionCode=$info.Code; versionName=$info.VersionName; certificateSha256=$info.Certificate; debuggable=$info.Debuggable }
}
$debugInfo = Get-ApkMetadata $apk
$releaseInfo = Get-ApkMetadata $releaseApk
if ($debugInfo.package -ne 'com.example.chatandroid.debug' -or $releaseInfo.package -ne 'com.example.chatandroid' -or $releaseInfo.debuggable) { throw 'APK distribution channels or release debug flag are invalid' }
foreach ($info in @($debugInfo,$releaseInfo)) {
    if ($info.versionCode -ne $manifest.androidCode -or $info.versionName -ne $manifest.android) { throw 'APK versions do not match release.json' }
}
if ($debugInfo.certificateSha256 -eq $releaseInfo.certificateSha256) { throw 'Release must use its private release key, not the legacy debug key' }
$expectedReleaseCertificate = [IO.File]::ReadAllText((Join-Path $repo 'android/RELEASE-CERTIFICATE.txt')).Trim().ToLowerInvariant()
if ($expectedReleaseCertificate -notmatch '^[0-9a-f]{64}$' -or $releaseInfo.certificateSha256 -ne $expectedReleaseCertificate) { throw 'Release APK certificate does not match the public release identity' }
if ($PreviousDebugApk) {
    $previous = Get-ApkMetadata $PreviousDebugApk
    if ($previous.package -ne $debugInfo.package -or $previous.certificateSha256 -ne $debugInfo.certificateSha256 -or $previous.versionCode -ge $debugInfo.versionCode) { throw 'Legacy debug update is incompatible with the previous package' }
}
# Never delete or overwrite a release. Build into a new directory after checks.
if (Test-Path -LiteralPath $release) { throw "Release already exists: $release" }
New-Item -ItemType Directory -Path $release -Force | Out-Null
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($windows,(Join-Path $release 'Chat-Client-Windows-x64.zip'),[IO.Compression.CompressionLevel]::Optimal,$true)
Copy-Item -LiteralPath $apk -Destination (Join-Path $release 'Chat-Android-arm64-v8a-debug.apk')
Copy-Item -LiteralPath $releaseApk -Destination (Join-Path $release 'Chat-Android-arm64-v8a-release.apk')
@{ debug=$debugInfo; release=$releaseInfo } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $release 'ANDROID-SIGNING.json') -Encoding utf8
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
# Client packages are published separately from the small Java deployment archive.
$updateStage = Join-Path $stage 'server-updates'
New-Item -ItemType Directory -Path $updateStage -Force | Out-Null
$updateEntries = @()
foreach ($channel in @(
    @{ platform='windows-x64'; file='Chat-Client-Windows-x64.zip'; version=$manifest.windows },
    @{ platform='android-arm64-v8a-debug'; file='Chat-Android-arm64-v8a-debug.apk'; version=$manifest.android; code=$manifest.androidCode },
    @{ platform='android-arm64-v8a-release'; file='Chat-Android-arm64-v8a-release.apk'; version=$manifest.android; code=$manifest.androidCode }
)) {
    $sourcePackage = Join-Path $release $channel.file
    Copy-Item -LiteralPath $sourcePackage -Destination (Join-Path $updateStage $channel.file)
    $entry = [ordered]@{ platform=$channel.platform; version=$channel.version; fileName=$channel.file; size=(Get-Item -LiteralPath $sourcePackage).Length; sha256=(Get-FileHash -LiteralPath $sourcePackage -Algorithm SHA256).Hash.ToLowerInvariant(); downloadPath=('/api/updates/files/'+$channel.platform) }
    if ($channel.code) { $entry.versionCode = $channel.code }
    $updateEntries += $entry
}
$updateManifest = @{ schemaVersion=1; releases=$updateEntries } | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $updateStage 'manifest.json'),$updateManifest,[Text.UTF8Encoding]::new($false))
& tar -czf (Join-Path $release 'Chat-Client-Updates.tar.gz') -C $stage server-updates
if ($LASTEXITCODE) { throw 'Client update archive failed' }
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
$updateGuide = [IO.File]::ReadAllText((Join-Path $repo 'deploy/ubuntu/CLIENT-UPDATES.md')).Replace('(UPGRADE.md)','(服务器升级说明.md)')
[IO.File]::WriteAllText((Join-Path $release '服务器发布安装包说明.md'),$updateGuide,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $repo 'release.json') -Destination $release
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination (Join-Path $release 'LICENSE-Chat.txt')
Copy-Item -LiteralPath (Join-Path $repo 'NOTICE.md') -Destination (Join-Path $release 'NOTICE-Chat.md')
Copy-Item -LiteralPath (Join-Path $repo 'android/RELEASE.md') -Destination (Join-Path $release 'Android安装与更新.md')
Copy-Item -LiteralPath (Join-Path $repo 'android/RELEASE-CERTIFICATE.txt') -Destination (Join-Path $release 'Android正式版证书指纹.txt')
$guide = @"
Chat v$($manifest.bundle)
Windows $($manifest.windows) / Android $($manifest.android) (code $($manifest.androidCode)) / server $($manifest.server)

先升级服务器，再更新客户端。具体命令见同目录的服务器升级说明.md。
Chat-Client-Windows-x64.zip：完整解压后运行 Chat-win32-x64/Chat.exe，保留同一 Windows 用户原本的应用数据。
Chat-Android-arm64-v8a-debug.apk：使用原签名覆盖更新；不要卸载或清空原应用数据。其他 ABI 可从源码构建。
Chat-Android-arm64-v8a-release.apk：正式签名新安装渠道；与旧调试版数据独立，不能继承旧调试版本地历史。见 Android安装与更新.md。
ANDROID-SIGNING.json：两条渠道的包名、版本与公开证书 SHA-256 指纹，不包含私钥。
Chat-Ubuntu-Deploy.tar.gz：上传并校验后用于 Ubuntu 24.04 部署，保留现有 HTTPS/TURN 配置。
Chat-Source.zip：完整项目源码、第三方对应源码、构建和验证脚本；不包含用户数据库或签名密钥。
Chat-Client-Updates.tar.gz：客户端服务器下载源，上传解压后用 publish-updates.sh 发布；不含用户数据。
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
