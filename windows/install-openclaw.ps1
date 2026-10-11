$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (Test-Path '.venv\Scripts\python.exe') {
    & .venv\Scripts\python.exe -m tools.openclaw_config --root . --install
    exit $LASTEXITCODE
}
Write-Host 'Use ChatPlanner.exe: Start all, then Install / repair planner tools.'
exit 1
