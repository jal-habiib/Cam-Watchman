[CmdletBinding()]
param(
    [string]$FfmpegPath
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$run = Join-Path $PSScriptRoot 'run'
$pidFile = Join-Path $run 'pids.json'
$state = Get-Content $pidFile -Raw | ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($FfmpegPath)) {
    $ffmpegCommand = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
    if ($ffmpegCommand) {
        $FfmpegPath = $ffmpegCommand.Source
    } else {
        $candidate = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages') -Filter ffmpeg.exe -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match 'yt-dlp\.FFmpeg.*\\bin\\ffmpeg\.exe$' } |
            Select-Object -First 1
        if ($candidate) { $FfmpegPath = $candidate.FullName }
    }
}

$clip = Join-Path $root 'testdata\real\crosswalk-720p.mp4'
if ([string]::IsNullOrWhiteSpace($FfmpegPath) -or !(Test-Path $FfmpegPath) -or !(Test-Path $clip)) {
    throw 'FFmpeg or the moving crosswalk sample is missing. Supply -FfmpegPath if FFmpeg is not on PATH.'
}

$names = @()
foreach ($camera in 1..4) {
    $names += "cam${camera}_main"
    $names += "cam${camera}_sub"
}
foreach ($name in $names) {
    $id = [int]$state.publishers.$name
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$id"
    if (!$proc -or $proc.Name -ne 'ffmpeg.exe' -or $proc.CommandLine -notlike "*rtsp://127.0.0.1:8554/$name*") {
        throw "Refusing to replace $name : PID $id is not the expected rig publisher."
    }
    Stop-Process -Id $id -Force
}
Start-Sleep -Milliseconds 500

function Start-RigPublisher([string]$name, [bool]$main) {
    $args = @('-hide_banner','-loglevel','warning','-re','-stream_loop','-1','-i',$clip,'-an')
    if ($main) {
        $args += @('-c','copy')
    } else {
        $args += @('-vf','scale=640:360','-r','10','-c:v','libx264','-preset','veryfast','-tune','zerolatency','-g','20','-pix_fmt','yuv420p')
    }
    $args += @('-f','rtsp','-rtsp_transport','tcp',"rtsp://127.0.0.1:8554/$name")
    $err = Join-Path $run "$name.log"
    $p = Start-Process -FilePath $FfmpegPath -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardError $err
    $state.publishers.$name = $p.Id
    Write-Output "$name -> PID $($p.Id)"
}

foreach ($camera in 1..4) {
    Start-RigPublisher "cam${camera}_main" $true
    Start-RigPublisher "cam${camera}_sub" $false
}
$state | ConvertTo-Json -Depth 4 | Set-Content -Path $pidFile -Encoding utf8
Write-Output 'Moving crosswalk footage is now publishing to all four cameras (four main AI/recording streams and four display substreams).'
