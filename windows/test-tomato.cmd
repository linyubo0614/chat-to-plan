@echo off
chcp 65001 >nul
cd /d "%~dp0.."
if not exist ".venv\Scripts\python.exe" (
  echo 请先运行 windows\start-assistant.cmd。
  pause
  exit /b 1
)
.venv\Scripts\python.exe -m tools.tomato_bridge --write-test
pause
