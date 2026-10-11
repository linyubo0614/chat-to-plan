@echo off
setlocal
cd /d "%~dp0.."
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
chcp 65001 >nul
if exist ".venv\Scripts\python.exe" goto run
 echo ERROR: Python environment missing. Run windows\start-assistant.cmd first.
set "RESULT=1"
goto finish
:run
echo Starting start-tomato-sync. Please keep this window open.
".venv\Scripts\python.exe" -u -m tools.tomato_bridge --serve %*
set "RESULT=%ERRORLEVEL%"
:finish
echo.
echo start-tomato-sync finished with exit code %RESULT%.
if not "%RESULT%"=="0" echo Please send the error text above. Do not send passwords or API keys.
if not "%CHAT_PLANNER_NO_PAUSE%"=="1" pause
exit /b %RESULT%
