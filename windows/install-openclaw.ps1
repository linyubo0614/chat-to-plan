$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$Root = (Get-Location).Path

function Invoke-OpenClaw([string[]]$Arguments) {
    # Capture output instead of exporting unrelated provider/channel diagnostics.
    $Previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $Output = @(& $script:OpenClawCommand @Arguments 2>&1 | ForEach-Object { [string]$_ }); $Code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $Previous }
    return @{ Code = $Code; Text = ($Output -join "`n") }
}

function Get-AllowedList([string]$Path) {
    $Result = Invoke-OpenClaw -Arguments @('config','get',$Path,'--json')
    if ($Result.Code -ne 0) {
        if ($Result.Text -match 'Config path is valid but unset:' -or $Result.Text -match 'Config path not found:') { return @() }
        throw "Cannot read OpenClaw $Path (exit $($Result.Code)); existing settings were preserved."
    }
    # A wrapper preserves JSON arrays with one element on Windows PowerShell 5.1.
    $Document = ('{"values":' + $Result.Text + '}') | ConvertFrom-Json
    $Values = $Document.values
    if ($null -ne $Values -and $Values -isnot [array]) { throw "OpenClaw $Path must be a list; settings were preserved." }
    return @($Values)
}

try {
    $Command = Get-Command openclaw.cmd -ErrorAction SilentlyContinue
    if (-not $Command) { $Command = Get-Command openclaw -ErrorAction SilentlyContinue }
    if (-not $Command) { throw 'OpenClaw was not found in this Windows terminal. If it runs in WSL, use the WSL instructions in docs/openclaw.md.' }
    $script:OpenClawCommand = $Command.Source
    $Version = Invoke-OpenClaw -Arguments @('--version')
    if ($Version.Code -ne 0) { throw 'OpenClaw could not start; run openclaw --version in the terminal where WeChat already works.' }
    Write-Host $Version.Text
    $Help = Invoke-OpenClaw -Arguments @('config','set','--help')
    if ($Help.Code -ne 0 -or $Help.Text -notmatch '--batch-file') { throw 'This OpenClaw version needs separate configuration support. Send only its version number; do not replace your working WeChat setup.' }
    $TokenFile = Join-Path $Root 'data\api-token'
    if (-not (Test-Path $TokenFile)) { throw 'Start windows\start-assistant.cmd first, then run this installer again.' }
    try {
        $Token = [IO.File]::ReadAllText($TokenFile).Trim()
        $Status = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/v1/status' -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 10
    } catch { throw 'The local Planner backend is not ready. Restart windows\start-assistant.cmd from the updated package.' }
    if ($Status.integrations.openclaw -ne 'structured_tools') { throw 'The running backend is an older version. Stop it and start the updated windows\start-assistant.cmd.' }
    Write-Host 'Installing two tools: record plans and query actual delivery status.'
    $PluginPath = Join-Path $Root 'openclaw'
    $Result = Invoke-OpenClaw -Arguments @('plugins','install',$PluginPath,'--force','--accept-capabilities','--no-enable')
    if ($Result.Code -ne 0) { throw "OpenClaw plugin installation failed (exit $($Result.Code)). No model or WeChat credential configuration was requested." }
    $Plugins = @(Get-AllowedList 'plugins.allow')
    if ($Plugins -notcontains 'chat-planner') { $Plugins += 'chat-planner' }
    $StrictTools = @(Get-AllowedList 'tools.allow')
    $ToolPath = 'tools.alsoAllow'
    $Tools = @(Get-AllowedList $ToolPath)
    if ($StrictTools.Count -gt 0) { $ToolPath = 'tools.allow'; $Tools = $StrictTools }
    foreach ($Tool in @('chat_planner_record','chat_planner_status')) {
        if ($Tools -notcontains $Tool) { $Tools += $Tool }
    }
    $Batch = @(
        @{ path = 'plugins.allow'; value = @($Plugins) },
        @{ path = 'plugins.entries.chat-planner.enabled'; value = $true },
        @{ path = 'plugins.entries.chat-planner.config.baseUrl'; value = 'http://127.0.0.1:8000' },
        @{ path = 'plugins.entries.chat-planner.config.tokenFile'; value = $TokenFile },
        @{ path = $ToolPath; value = @($Tools) }
    )
    $BatchFile = Join-Path $Root 'data\openclaw-planner-config.batch.json'
    $Utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($BatchFile,($Batch | ConvertTo-Json -Depth 8),$Utf8)
    try {
        $Result = Invoke-OpenClaw -Arguments @('config','set','--batch-file',$BatchFile,'--dry-run')
        if ($Result.Code -ne 0) { throw "OpenClaw rejected Planner settings (exit $($Result.Code)); settings were not applied." }
        $Result = Invoke-OpenClaw -Arguments @('config','set','--batch-file',$BatchFile)
        if ($Result.Code -ne 0) { throw "OpenClaw could not save Planner settings (exit $($Result.Code))." }
    } finally { Remove-Item $BatchFile -ErrorAction SilentlyContinue }
    $Result = Invoke-OpenClaw -Arguments @('plugins','inspect','chat-planner','--runtime','--json')
    if ($Result.Code -ne 0) { throw "OpenClaw plugin inspection failed (exit $($Result.Code))." }
    $Inspection = $Result.Text | ConvertFrom-Json
    if ($Inspection.plugin.status -ne 'loaded' -or
        $Inspection.plugin.toolNames -notcontains 'chat_planner_record' -or
        $Inspection.plugin.toolNames -notcontains 'chat_planner_status') { throw 'Planner plugin did not load both tools. Send the OpenClaw version number for compatibility checks.' }
    Write-Host 'OPENCLAW_PLANNER_INSTALLED: both tools loaded; existing model and channel settings preserved.'
    Write-Host 'Restart your OpenClaw gateway the same way you normally start it, then open a new WeChat conversation turn.'
    Write-Host 'Keep the Planner backend and HITA compatible bridge online. Daily tasks and classified memos are saved in the Planner app.'
    exit 0
} catch {
    Write-Host ([string]$_.Exception.Message)
    Write-Host 'Do not send API keys, connection tokens, QR codes, or OpenClaw configuration files.'
    exit 1
}
