@echo off
setlocal DisableDelayedExpansion
title Forge Remote E
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0Open-ForgeRemote.ps1"
if not errorlevel 1 exit /b 0
echo.
pause
exit /b 1
