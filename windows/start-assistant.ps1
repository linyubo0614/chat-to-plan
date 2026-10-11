$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$Data = Join-Path (Get-Location) 'data'
New-Item -ItemType Directory -Path $Data -Force | Out-Null
$Account = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $Data /inheritance:r /grant:r "${Account}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the local data directory.' }
if (-not (Test-Path '.venv\Scripts\python.exe')) {
    if (-not (Get-Command py.exe -ErrorAction SilentlyContinue)) {
        Write-Host 'Please install Python 3.12 from python.org, then run this file again.'
        Start-Process 'https://www.python.org/downloads/windows/'
        exit 1
    }
    & py.exe -3.12 -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.12 is required.' }
}
& .venv\Scripts\python.exe -m pip install --disable-pip-version-check -r requirements.windows.txt
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
$TokenFile = Join-Path $Data 'api-token'
if (-not (Test-Path $TokenFile)) {
    $Bytes = New-Object byte[] 32
    $Random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $Random.GetBytes($Bytes) } finally { $Random.Dispose() }
    $Token = [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    [System.IO.File]::WriteAllText($TokenFile, $Token)
}
$env:ASSISTANT_API_TOKEN = [System.IO.File]::ReadAllText($TokenFile).Trim()
$SecretFile = Join-Path $Data 'windows-secrets.dpapi'
if (Test-Path $SecretFile) {
    Add-Type -AssemblyName System.Security
    $Encrypted = [System.IO.File]::ReadAllBytes($SecretFile)
    $Plain = [System.Security.Cryptography.ProtectedData]::Unprotect($Encrypted, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    $Config = [System.Text.Encoding]::UTF8.GetString($Plain) | ConvertFrom-Json
    foreach ($Name in @('WECHAT_CORP_ID','WECHAT_KF_SECRET','WECHAT_CALLBACK_TOKEN','WECHAT_ENCODING_AES_KEY','WECHAT_ALLOWED_USERS')) {
        [Environment]::SetEnvironmentVariable($Name, [string]$Config.$Name, 'Process')
    }
    [Array]::Clear($Plain,0,$Plain.Length)
}
Write-Host 'Assistant is starting on this PC. Keep this window open. Close it with Ctrl+C.'
Write-Host 'Next: run windows\test-tomato.cmd in another window to log in and verify a test memo.'
& .venv\Scripts\python.exe -m uvicorn backend.app:create_app --factory --host 127.0.0.1 --port 8000 --workers 1 --no-access-log --timeout-graceful-shutdown 120
exit $LASTEXITCODE
