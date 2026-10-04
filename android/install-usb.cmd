@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-usb.ps1" %*
pause
