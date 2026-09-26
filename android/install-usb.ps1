$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$adbCandidates = @()
if ($env:ANDROID_HOME) { $adbCandidates += Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' }
if ($env:ANDROID_SDK_ROOT) { $adbCandidates += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools/adb.exe' }
$adbCandidates += Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$adbCandidates += Join-Path $project '../../../work/android-tools/sdk/platform-tools/adb.exe'
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) { $adbCandidates += $adbCommand.Source }
$adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$adb) { throw 'Android platform-tools not found. Set ANDROID_HOME to the Android SDK folder.' }
$devices = @(& $adb devices)
$ready = @($devices | Where-Object { $_ -match '^\S+\s+device$' })
if ($ready.Count -ne 1) {
    $devices | Write-Host
    throw 'Connect exactly one phone, enable USB debugging, unlock it and accept the authorization prompt.'
}
$serial = ($ready[0] -split '\s+')[0]
$abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
if ($abi -notmatch '^[a-z0-9_-]+$') { throw 'Could not identify phone CPU architecture.' }
$apk = Join-Path $project "app/build/outputs/apk/debug/app-$abi-debug.apk"
if (!(Test-Path -LiteralPath $apk) -and $abi -eq 'arm64-v8a') { $apk = Join-Path $project '../../Chat-Android-arm64-v8a-debug.apk' }
if (!(Test-Path -LiteralPath $apk)) { throw "No APK for $abi. Build first with gradlew.bat assembleDebug." }
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Check the phone for an installation prompt.' }
& $adb -s $serial reverse tcp:8082 tcp:8082
if ($LASTEXITCODE -ne 0) { throw 'USB port forwarding failed.' }
& $adb -s $serial shell am start -n 'com.example.chatandroid.debug/com.example.chatandroid.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host 'Ready. Server: http://127.0.0.1:8082. Keep USB connected. Register a new phone account.'
