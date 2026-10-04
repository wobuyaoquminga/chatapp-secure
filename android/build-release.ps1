param()
$ErrorActionPreference = 'Stop'
$required = @('JAVA_HOME','CHAT_ANDROID_RELEASE_KEYSTORE','CHAT_ANDROID_RELEASE_STORE_PASSWORD','CHAT_ANDROID_RELEASE_KEY_ALIAS','CHAT_ANDROID_RELEASE_KEY_PASSWORD')
foreach ($name in $required) {
    if (![Environment]::GetEnvironmentVariable($name)) { throw "Required environment variable: $name. See RELEASE.md." }
}
if (!(Test-Path -LiteralPath $env:CHAT_ANDROID_RELEASE_KEYSTORE -PathType Leaf)) { throw 'Signing keystore does not exist.' }
if (!$env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat --no-daemon assembleRelease --no-problems-report
    if ($LASTEXITCODE) { throw 'Signed release build failed. No unsigned APK should be distributed.' }
} finally { Pop-Location }
