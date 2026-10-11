@echo off
cd /d "%~dp0.."
where cloudflared.exe >nul 2>nul
if errorlevel 1 (
  echo Install Cloudflare Tunnel first: winget install --id Cloudflare.cloudflared --exact
  echo Then reopen this window. The HTTPS address is for testing; it changes after restart.
  pause
  exit /b 1
)
echo Keep start-assistant.cmd running. Copy the HTTPS address shown below into your private App settings.
echo This is a temporary test link, not the final stable WeChat callback address.
cloudflared.exe tunnel --url http://127.0.0.1:8000 --no-autoupdate
pause
