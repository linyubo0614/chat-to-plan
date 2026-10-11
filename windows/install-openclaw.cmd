@echo off
setlocal
cd /d "%~dp0.."
echo Installing Chat Planner tools into your local OpenClaw.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-openclaw.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
echo install-openclaw finished with exit code %RESULT%.
if not "%CHAT_PLANNER_NO_PAUSE%"=="1" pause
exit /b %RESULT%
