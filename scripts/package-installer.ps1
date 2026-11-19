#requires -Version 5.1
[CmdletBinding()]
param([string]$Version = "0.1.0")
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$appImage = Join-Path $root "dist\CamWatchman"
$stageRoot = Join-Path $root "dist\installer-stage"
$stage = Join-Path $stageRoot "CamWatchman"
$compiler = Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 7\ISCC.exe"
if (-not (Test-Path (Join-Path $appImage "CamWatchman.exe"))) { throw "Missing app-image. Build it first." }
if (-not (Test-Path $compiler)) { throw "Inno Setup compiler missing: $compiler" }
Remove-Item $stageRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Write-Host "[installer] staging clean app-image"
& robocopy $appImage $stage /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP /XD (Join-Path $appImage "tmp") /XF "cameras.properties" "*.db" "*.db-wal" "*.db-shm" "*.log" "*.mkv" "*.mp4" "*.jpg" "*.png" | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE)" }
# Ship only a safe example. Real camera credentials are entered by the user in Manage Cameras.
$example = @'
# Cam Watchman first run: no camera configured yet.
# Add your camera in the app:  Cameras (header) -> + Add camera, then paste its RTSP URL.
# The app opens the camera manager automatically when no camera is set up.
# RTSP URL format:  rtsp://[username:password@]camera-ip:port/stream-path
base.data.dir=
recording.segment.seconds=15
retention.days=7
retention.size.gb=40
retention.min.free.gb=10
ai.rate.fps=1
'@
Set-Content (Join-Path $stage "cameras.properties.example") $example -Encoding ascii
$appExample = Join-Path $stage "app\cameras.properties.example"
if (Test-Path (Split-Path $appExample -Parent)) {
    Set-Content $appExample $example -Encoding ascii
}
$bad = Get-ChildItem $stage -Recurse -File | Where-Object { $_.Name -match '^(cameras\.properties|.*\.db(-wal|-shm)?|.*\.log|.*\.(mkv|mp4|jpg|png))$' }
if ($bad) { $bad | ForEach-Object { Write-Error $_.FullName }; throw "Sensitive local files found in installer stage" }
$iss = Join-Path $PSScriptRoot "CamWatchman.iss"
Write-Host "[installer] compiling $iss"
& $compiler "/DAppVersion=$Version" "/DStageDir=$stage" $iss
if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }
$setup = Join-Path $root "dist\CamWatchman-Setup-$Version-win64.exe"
if (-not (Test-Path $setup)) { throw "ISCC reported success but installer not found: $setup" }
$hash = Get-FileHash $setup -Algorithm SHA256
$size = [math]::Round((Get-Item $setup).Length / 1MB, 1)
Write-Host "[installer] built: $setup ($size MB)"
Write-Host "[installer] SHA256: $($hash.Hash)"
Write-Host "[installer] user config/database/recordings remain under %LOCALAPPDATA%\CamWatchman"
