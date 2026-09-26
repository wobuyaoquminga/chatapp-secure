@echo off
setlocal
if exist "%USERPROFILE%\.android\debug.keystore" set "CHAT_ANDROID_DEBUG_KEYSTORE=%USERPROFILE%\.android\debug.keystore"
if not defined JAVA_HOME (
  echo Please set JAVA_HOME to a JDK 21 installation. See android/README.md.
  exit /b 1
)
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME (
  echo Please set ANDROID_HOME to an Android SDK installation. See android/README.md.
  exit /b 1
)
set "CHAT_ROOT=%~dp0.."
cd /d "%~dp0"
call gradlew.bat assembleDebug testDebugUnitTest lintDebug --no-problems-report
exit /b %ERRORLEVEL%
