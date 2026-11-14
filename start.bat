@echo off
rem 双击即可启动（内部调用 PowerShell 脚本）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
if errorlevel 1 pause
