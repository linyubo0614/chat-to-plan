$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path 'data\api-token')) { throw 'Run start-assistant.cmd first.' }
Add-Type -AssemblyName System.Security
function Read-PrivateValue([string]$Prompt) {
    $Value = Read-Host $Prompt -AsSecureString
    $Pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Pointer) }
}
$Config = @{}
$Config.WECHAT_CORP_ID = Read-Host 'CorpID'
$Config.WECHAT_KF_SECRET = Read-PrivateValue 'WeChat customer-service Secret (hidden input)'
$Config.WECHAT_CALLBACK_TOKEN = Read-PrivateValue 'Callback Token (same as WeChat console)'
$Config.WECHAT_ENCODING_AES_KEY = Read-PrivateValue '43-character EncodingAESKey'
if ($Config.WECHAT_ENCODING_AES_KEY.Length -ne 43) { throw 'EncodingAESKey must have 43 characters.' }
$Config.WECHAT_ALLOWED_USERS = Read-Host 'Your external_userid (comma separated; blank denies all messages)'
$Plain = [System.Text.Encoding]::UTF8.GetBytes(($Config | ConvertTo-Json -Compress))
try {
    $Encrypted = [System.Security.Cryptography.ProtectedData]::Protect($Plain,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    [System.IO.File]::WriteAllBytes((Join-Path (Get-Location) 'data\windows-secrets.dpapi'),$Encrypted)
} finally { [Array]::Clear($Plain,0,$Plain.Length) }
Write-Host 'Saved locally with Windows account encryption. Restart the assistant to apply.'
