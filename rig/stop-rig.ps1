# Stop the Phase 0e rig: only the processes rig\start-rig.ps1 recorded in rig\run\pids.json.
# Anything else (an app under test, VLC, another agent's process) is left alone.
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$Quiet
)

Set-StrictMode -Version Latest
$pidFile = Join-Path $Root 'rig\run\pids.json'
if (-not (Test-Path $pidFile)) {
    if (-not $Quiet) { Write-Host '[rig] nothing recorded to stop.' }
    exit 0
}

$state = Get-Content $pidFile -Raw | ConvertFrom-Json
$ids = @()
if ($state.mediamtx) { $ids += [int]$state.mediamtx }
if ($state.publishers) {
    foreach ($p in $state.publishers.PSObject.Properties) { $ids += [int]$p.Value }
}

foreach ($id in ($ids | Sort-Object -Unique)) {
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($proc) {
        if (-not $Quiet) { Write-Host "[rig] stopping $($proc.ProcessName) pid=$id" }
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    }
}
Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
if (-not $Quiet) { Write-Host "[rig] stopped $($ids | Sort-Object -Unique | Measure-Object | Select-Object -ExpandProperty Count) process(es)." }
