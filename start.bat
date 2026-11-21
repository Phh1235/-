@echo off
setlocal
rem One-click launcher: starts Java backend + Python agent, then opens the demo page.
rem Uses -ExecutionPolicy Bypass so it also works when scripts are blocked by policy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
if errorlevel 1 (
  echo.
  echo Startup failed. Check the logs folder for java.err.log / python.err.log
  pause
  exit /b 1
)
endlocal
