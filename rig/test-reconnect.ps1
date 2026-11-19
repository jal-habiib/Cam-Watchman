# Phase 0e evidence: induce a disconnect, prove MediaMTX drops the path (a publisher kill
# removes the path from the API until a publisher returns), then restart the publisher and
# prove the path recovers. This is the rig-side half of acceptance test "Reconnect"
# (plan p.9); the app-side half is 1a.
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Path = 'cam2_sub',
    [int]$GapSeconds = 5
)
Set-StrictMode -Version Latest

$run      = Join-Path $Root 'rig\run'
$pidFile  = Join-Path $run 'pids.json'
$evidence = Join-Path $Root 'rig\evidence\reconnect.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $evidence) | Out-Null

function Get-PathState {
    try { $items = (Invoke-RestMethod 'http://127.0.0.1:9997/v3/paths/list' -TimeoutSec 3).items } catch { $items = @() }
    return $items | Where-Object { $_.name -eq $Path }
}

function Snapshot([string]$tag) {
    $p = Get-PathState
    if ($null -eq $p) {
        $ready = 'absent'; $readers = 0; $source = '-'
    } else {
        $ready = [string]$p.ready; $readers = @($p.readers).Count; $source = $p.source.type
    }
    $line = '{0}  {1,-9} ready={2,-6} readers={3} source={4}' -f (Get-Date -Format 'HH:mm:ss.fff'), $tag, $ready, $readers, $source
    Write-Host $line
    Add-Content -Path $evidence -Value $line
}

if (-not (Test-Path $pidFile)) { throw 'rig\run\pids.json missing - start the rig first.' }
$state = Get-Content $pidFile -Raw | ConvertFrom-Json
$id = [int]$state.publishers.$Path
if (-not $id) { throw "no publisher pid recorded for $Path" }

Add-Content -Path $evidence -Value ("--- reconnect test {0} path={1} ---" -f (Get-Date -Format s), $Path)
Snapshot 'before'

Stop-Process -Id $id -Force
Write-Host "[rig] killed publisher $Path pid=$id; waiting ${GapSeconds}s for MediaMTX to drop the path"
Start-Sleep -Seconds $GapSeconds
Snapshot 'killed'

$clip = Join-Path $Root "testdata\synth_$($Path.Split('_')[0]).mp4"
$ffArgs = @('-hide_banner','-loglevel','warning','-re','-stream_loop','-1','-i',$clip,'-an',
            '-vf','scale=640:360','-r','10','-c:v','libx264','-preset','veryfast','-tune','zerolatency',
            '-g','20','-pix_fmt','yuv420p','-f','rtsp','-rtsp_transport','tcp',"rtsp://127.0.0.1:8554/$Path")
$p = Start-Process -FilePath 'ffmpeg' -ArgumentList $ffArgs -WindowStyle Hidden -PassThru `
    -RedirectStandardError (Join-Path $run "$Path.log")
$state.publishers.$Path = $p.Id
$state | ConvertTo-Json -Depth 4 | Set-Content $pidFile -Encoding utf8
Write-Host "[rig] restarted publisher $Path pid=$($p.Id)"

$deadline = (Get-Date).AddSeconds(20)
$ok = $false
do {
    Start-Sleep -Milliseconds 400
    $p2 = Get-PathState
    if ($null -ne $p2 -and $p2.ready) { $ok = $true }
} until ($ok -or (Get-Date) -gt $deadline)
Snapshot 'recovered'
if (-not $ok) { Write-Warning "[rig] $Path did not recover within 20 s" }
Write-Host "[rig] evidence appended to $evidence"
