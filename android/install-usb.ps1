param(
    [ValidateSet('Debug','Release')][string]$Variant = 'Debug',
    [string]$Version = '',
    [string]$ApkPath = '',
    [ValidatePattern('^[a-z0-9_-]+$')][string]$Abi = 'arm64-v8a',
    [string]$ExpectedCertificateFingerprint = '',
    [switch]$AllowSeparateInstall,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$projectItem = Get-Item -LiteralPath $project
if ($projectItem.Target) { $project = [string](@($projectItem.Target)[0]) }
$outputs = [IO.Path]::GetFullPath((Join-Path $project '../..'))
$toolRoot = [IO.Path]::GetFullPath((Join-Path $project '../../../work/android-tools/sdk'))
$tempRoot = [IO.Path]::GetTempPath().TrimEnd('\')
if ($tempRoot -match '[^\x00-\x7f]') {
    # Older aapt builds cannot read non-ASCII Windows APK paths.
    $tempRoot = Join-Path $env:PUBLIC 'ChatInstallTemp'
}
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

function New-PrivateTempDirectory {
    $folder = Join-Path $tempRoot ('chat-install-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $folder | Out-Null
    try {
        $acl = Get-Acl -LiteralPath $folder
        $acl.SetAccessRuleProtection($true, $false)
        $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($owner, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        Set-Acl -LiteralPath $folder -AclObject $acl
        return $folder
    } catch {
        Remove-Item -LiteralPath $folder -Recurse -Force
        throw '无法创建私有临时目录，已取消校验。'
    }
}
function Remove-PrivateTempDirectory([string]$folder) {
    if ($folder -and (Split-Path $folder -Parent).TrimEnd('\') -eq $tempRoot.TrimEnd('\') -and
        (Split-Path $folder -Leaf) -match '^chat-install-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $folder -Recurse -Force
    }
}

function Find-SdkTool([string]$name) {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'), $toolRoot) |
        Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }
    foreach ($root in $roots) {
        $folders = @(Get-ChildItem -LiteralPath (Join-Path $root 'build-tools') -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [version]$_.Name } -Descending)
        foreach ($folder in $folders) {
            $candidate = Join-Path $folder.FullName $name
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
    }
    throw "找不到 Android SDK build-tools 中的 $name，请设置 ANDROID_HOME。"
}
function Read-Apk([string]$path, [string]$aapt, [string]$signer) {
    $folder = New-PrivateTempDirectory
    try {
        $staged = Join-Path $folder 'check.apk'
        Copy-Item -LiteralPath $path -Destination $staged
        $badging = @(& $aapt dump badging $staged 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'APK 元信息读取失败，请检查文件是否完整。' }
        $line = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
        if (!$line -or $line -notmatch "name='([^']+)'\s+versionCode='([0-9]+)'\s+versionName='([^']*)'") {
            throw 'APK 缺少有效包名或版本号。'
        }
        $package = $Matches[1]; $code = [long]$Matches[2]; $versionName = $Matches[3]
        if ($code -lt 1) { throw 'APK 的 versionCode 无效。' }
        $certificate = @(& $signer verify --print-certs $staged 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
        $digests = @($certificate | ForEach-Object {
            if ($_ -match '^Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$') { $Matches[1].ToLowerInvariant() }
        })
        if ($digests.Count -ne 1) { throw 'APK 证书信息不唯一或缺失，拒绝安装。' }
        return [pscustomobject]@{ Package = $package; Code = $code; VersionName = $versionName; Certificate = $digests[0]; Debuggable = [bool]($badging -match '^application-debuggable') }
    } finally { Remove-PrivateTempDirectory $folder }
}
function Get-InstalledPath([string]$package) {
    $result = @(& $adb -s $serial shell pm path $package 2>&1)
    if ($LASTEXITCODE -ne 0) { throw '读取手机已安装应用信息失败。' }
    $paths = @($result | Where-Object { $_ -match '^package:' })
    if ($paths.Count -eq 0) {
        if (@($result | Where-Object { $_ -and $_ -notmatch '^\s*$' }).Count -gt 0) { throw '手机返回了无法识别的应用信息。' }
        return $null
    }
    $base = $paths | Where-Object { $_ -match '/base\.apk\s*$' } | Select-Object -First 1
    if (!$base) { throw '找不到手机上已安装应用的 base.apk。' }
    return ($base -replace '^package:', '').Trim()
}

if ($Version -and $ApkPath) { throw '-Version 和 -ApkPath 只能选择一个。' }
if ($Version -and $Version -notmatch '^\d+\.\d+\.\d+$') { throw '版本号格式应为 0.6.0。' }
$aapt = Find-SdkTool 'aapt.exe'
$signer = Find-SdkTool 'apksigner.bat'
$package = if ($Variant -eq 'Release') { 'com.example.chatandroid' } else { 'com.example.chatandroid.debug' }
$variantLower = $Variant.ToLowerInvariant()

if (!$ValidateOnly) {
    $adbCandidates = @()
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'), $toolRoot)) {
        if ($root) { $adbCandidates += Join-Path $root 'platform-tools/adb.exe' }
    }
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $adbCandidates += $adbCommand.Source }
    $adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (!$adb) { throw '找不到 adb，请设置 ANDROID_HOME。' }
    $devices = @(& $adb devices)
    $ready = @($devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($ready.Count -ne 1) { throw '请只连接一台已解锁且已授权 USB 调试的手机。' }
    $serial = ($ready[0] -split '\s+')[0]
    $Abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
    if ($LASTEXITCODE -ne 0 -or $Abi -notmatch '^[a-z0-9_-]+$') { throw '无法识别手机 CPU 架构。' }
}

if ($ApkPath) {
    $apk = [IO.Path]::GetFullPath($ApkPath)
} elseif ($Version) {
    # An explicit bundle version always wins over any local build output.
    $apk = Join-Path (Join-Path $outputs "Chat-v$Version") "Chat-Android-$Abi-$variantLower.apk"
} else {
    $apk = Join-Path $project "app/build/outputs/apk/$variantLower/app-$Abi-$variantLower.apk"
    if (!(Test-Path -LiteralPath $apk -PathType Leaf)) {
        $release = Get-ChildItem -LiteralPath $outputs -Directory -Filter 'Chat-v*' |
            Where-Object { $_.Name -match '^Chat-v\d+\.\d+\.\d+$' } |
            Sort-Object { [version]$_.Name.Substring(6) } -Descending |
            Select-Object -First 1 -ExpandProperty FullName
        if ($release) { $apk = Join-Path $release "Chat-Android-$Abi-$variantLower.apk" }
    }
}
if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw "找不到 $Variant / $Abi 安装包，请检查版本、路径或先构建 APK。" }
$apk = (Resolve-Path -LiteralPath $apk).Path
$selectedTemp = New-PrivateTempDirectory
try {
$selectedApk = Join-Path $selectedTemp 'target.apk'
Copy-Item -LiteralPath $apk -Destination $selectedApk
$target = Read-Apk $selectedApk $aapt $signer
if ($target.Package -ne $package) { throw "APK 包名与 -Variant $Variant 不一致，拒绝安装。" }
if ($Version) {
    $manifest = Get-Content -LiteralPath (Join-Path (Split-Path $project -Parent) 'release.json') -Raw | ConvertFrom-Json
    if ($Version -eq $manifest.bundle -and
        ($target.Code -ne [long]$manifest.androidCode -or $target.VersionName -ne [string]$manifest.android)) {
        throw '所选成品目录中的 APK 与当前发布清单版本不符，已取消安装。'
    }
}
if (!$ExpectedCertificateFingerprint -and $Variant -eq 'Release') {
    $pin = Join-Path $project 'RELEASE-CERTIFICATE.txt'
    if (!(Test-Path -LiteralPath $pin -PathType Leaf)) { throw '缺少 Release 证书指纹文件，已取消安装。' }
    $ExpectedCertificateFingerprint = (Get-Content -LiteralPath $pin -Raw).Trim()
}
if ($ExpectedCertificateFingerprint) {
    $fingerprint = $ExpectedCertificateFingerprint.Replace(':', '').ToLowerInvariant()
    if ($fingerprint -notmatch '^[0-9a-f]{64}$') { throw '预期证书指纹必须为 64 位 SHA-256 十六进制值。' }
    if ($target.Certificate -ne $fingerprint) { throw 'APK 签名与预期证书指纹不符，已取消安装。' }
}
Write-Host "已校验 $Variant APK：版本 $($target.VersionName)，versionCode $($target.Code)，签名有效。"
if ($ValidateOnly) { $target; return }

if ($Variant -eq 'Release' -and !(Get-InstalledPath $package) -and (Get-InstalledPath 'com.example.chatandroid.debug') -and !$AllowSeparateInstall) {
    throw '手机上已有独立的 Debug 版。Release 版使用不同包名和独立数据；如确认要并存，请显式添加 -AllowSeparateInstall。'
}
$installedPath = Get-InstalledPath $package
if ($installedPath) {
    $temporary = New-PrivateTempDirectory
    try {
        $existingApk = Join-Path $temporary 'base.apk'
        & $adb -s $serial pull $installedPath $existingApk *> $null
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $existingApk -PathType Leaf)) { throw '无法读取手机上的已安装 APK，已取消覆盖安装。' }
        $existing = Read-Apk $existingApk $aapt $signer
        if ($existing.Package -ne $package) { throw '手机上已安装应用的包名异常，已取消安装。' }
        if ($existing.Certificate -ne $target.Certificate) { throw '新旧 APK 签名不同，无法保留数据覆盖更新；已取消安装。' }
        if ($target.Code -lt $existing.Code) { throw '新 APK 的 versionCode 较低，拒绝降级安装。' }
    } finally {
        Remove-PrivateTempDirectory $temporary
    }
}
& $adb -s $serial install -r $selectedApk
if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败；请查看手机上的安装提示。' }
& $adb -s $serial reverse tcp:8082 tcp:8082
if ($LASTEXITCODE -ne 0) { throw 'USB 端口转发失败。' }
& $adb -s $serial shell am start -n "$package/com.example.chatandroid.MainActivity"
if ($LASTEXITCODE -ne 0) { throw '应用启动失败。' }
Write-Host '安装完成。请保持 USB 连接，服务器地址为 http://127.0.0.1:8082。'
} finally { Remove-PrivateTempDirectory $selectedTemp }
