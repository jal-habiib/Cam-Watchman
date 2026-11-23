# Phase 0e test rig - MediaMTX + looped FFmpeg publishers.
#
#   cam1_main..cam4_main 1280x720 @25 H.264, independent recording/AI inputs
#   cam1_sub..cam4_sub   640x360 @10 H.264, scaled display streams
#
# Usage:  powershell -ExecutionPolicy Bypass -File rig\start-rig.ps1
# Stop:   powershell -ExecutionPolicy Bypass -File rig\stop-rig.ps1
# Status: powershell -ExecutionPolicy Bypass -File rig\status-rig.ps1
#
# Test clips are generated on first run into testdata\ and are git-ignored.
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$KeepExisting
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$rig   = Join-Path $Root 'rig'
$run   = Join-Path $rig 'run'
$media = Join-Path $Root 'testdata'
$exe   = Join-Path $Root 'tools\mediamtx\mediamtx.exe'

if (-not (Test-Path $exe)) {
    throw "MediaMTX binary missing: $exe. Extract mediamtx_v1.21.4_windows_amd64.zip into tools\mediamtx\."
}
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw 'ffmpeg is not on PATH.'
}

New-Item -ItemType Directory -Force -Path $run, $media | Out-Null

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(300)
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

if (-not $KeepExisting) {
    & (Join-Path $rig 'stop-rig.ps1') -Root $Root -Quiet | Out-Null
    Start-Sleep -Milliseconds 500
}

function Ensure-Clip([string]$Path, [string]$Source) {
    if (Test-Path $Path) { return }
    Write-Host "[rig] generating $([IO.Path]::GetFileName($Path))"
    & ffmpeg -hide_banner -loglevel error -y -f lavfi -i $Source -t 30 `
        -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 $Path
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed to generate $Path" }
}

$clips = @{
    cam1 = Join-Path $media 'synth_cam1.mp4'
    cam2 = Join-Path $media 'synth_cam2.mp4'
    cam3 = Join-Path $media 'synth_cam3.mp4'
    cam4 = Join-Path $media 'synth_cam4.mp4'
}
Ensure-Clip $clips.cam1 'testsrc2=size=1280x720:rate=25'
Ensure-Clip $clips.cam2 'smptehdbars=size=1280x720:rate=25'
Ensure-Clip $clips.cam3 'testsrc=size=1280x720:rate=25'
Ensure-Clip $clips.cam4 'gradients=size=1280x720:rate=25:speed=0.02'

$pidFile = Join-Path $run 'pids.json'
$state  = @{ mediamtx = 0; publishers = @{} }

# --- MediaMTX ---------------------------------------------------------------------------
$mtxOut = Join-Path $run 'mediamtx.log'
$mtxErr = Join-Path $run 'mediamtx.err.log'
$mtx = Start-Process -FilePath $exe -ArgumentList @((Join-Path $Root 'tools\mediamtx\mediamtx.yml')) `
    -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $mtxOut -RedirectStandardError $mtxErr
$state.mediamtx = $mtx.Id

$deadline = (Get-Date).AddSeconds(15)
do {
    Start-Sleep -Milliseconds 200
    $up = Test-Port 8554
} until ($up -or (Get-Date) -gt $deadline)
if (-not $up) { throw "MediaMTX did not open 127.0.0.1:8554. See $mtxErr" }
Write-Host "[rig] mediamtx pid=$($mtx.Id) rtsp=127.0.0.1:8554 api=http://127.0.0.1:9997"

# --- publishers -------------------------------------------------------------------------
function Start-Publisher([string]$Name, [string]$Clip, [string[]]$Extra) {
    $ffArgs = @('-hide_banner', '-loglevel', 'warning', '-re', '-stream_loop', '-1',
                '-i', $Clip, '-an') + $Extra +
              @('-f', 'rtsp', '-rtsp_transport', 'tcp', "rtsp://127.0.0.1:8554/$Name")
    $out = Join-Path $run "$Name.log"
    $p = Start-Process -FilePath 'ffmpeg' -ArgumentList $ffArgs -WindowStyle Hidden -PassThru `
        -RedirectStandardError $out
    $state.publishers[$Name] = $p.Id
    Write-Host ("[rig] {0,-9} pid={1}" -f $Name, $p.Id)
}

# Camera main streams: one independent full-resolution input per camera.
foreach ($c in 1..4) {
    Start-Publisher "cam${c}_main" $clips["cam$c"] @('-c', 'copy')
}
# Display substreams: scaled down, low frame rate for the camera wall.
foreach ($c in 1..4) {
    Start-Publisher "cam${c}_sub" $clips["cam$c"] @(
        '-vf', 'scale=640:360', '-r', '10', '-c:v', 'libx264',
        '-preset', 'veryfast', '-tune', 'zerolatency', '-g', '20', '-pix_fmt', 'yuv420p')
}

$state | ConvertTo-Json -Depth 4 | Set-Content -Path $pidFile -Encoding utf8
Write-Host "[rig] pids -> $pidFile"

# --- wait until every path is publishing -------------------------------------------------
$names = (1..4 | ForEach-Object { "cam${_}_main" }) + (1..4 | ForEach-Object { "cam${_}_sub" })
$deadline = (Get-Date).AddSeconds(25)
$ready = @()
do {
    Start-Sleep -Milliseconds 500
    try {
        $paths = (Invoke-RestMethod -Uri 'http://127.0.0.1:9997/v3/paths/list' -TimeoutSec 3).items
        $ready = @($paths | Where-Object { $_.ready } | ForEach-Object { $_.name })
    } catch { $ready = @() }
} until ((Compare-Object $names $ready -SyncWindow 0 | Measure-Object).Count -eq 0 -or (Get-Date) -gt $deadline)

if ((Compare-Object $names $ready | Measure-Object).Count -gt 0) {
    Write-Warning "[rig] not all paths ready yet: ready=$($ready -join ',')"
} else {
    Write-Host "[rig] all paths ready: $($names -join ', ')"
}
Write-Host '[rig] ready. Open rtsp://127.0.0.1:8554/cam1_main in VLC to eyeball it.'
