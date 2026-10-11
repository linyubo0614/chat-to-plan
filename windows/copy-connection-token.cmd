@echo off
cd /d "%~dp0.."
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; Get-Content -Raw 'data/api-token' | ForEach-Object { Set-Clipboard -Value $_.Trim() }; Write-Host 'Connection token copied to clipboard. Paste it only into your private app settings.'"
pause
