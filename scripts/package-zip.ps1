#requires -Version 5.1
<#
.SYNOPSIS
    Packages the built app-image into a distributable zip, a safe per-user installer and an uninstaller.

.DESCRIPTION
    WiX-free distribution path: no candle/light/wix.exe, no MSI, no admin rights.
    Run scripts/package-appimage.ps1 first (it produces dist\CamWatchman).

    This script is deliberately paranoid about user data: the app-image may have been used for a
    smoke test, so `cameras.properties`, `tmp\`, databases, logs, recordings and snapshots are
    excluded from the archive and the staged tree is audited before it is compressed. Run
    scripts/audit-archive.ps1 on the result for the independent archive check.

.EXAMPLE
    pwsh scripts/package-zip.ps1 -Version 0.1.0
#>
[CmdletBinding()]
param(
    [string]$Version = "0.1.0",
    [switch]$KeepStage
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$image = Join-Path $root 'dist\CamWatchman'
if (-not (Test-Path (Join-Path $image 'CamWatchman.exe'))) {
    throw "missing app-image: run scripts/package-appimage.ps1 first"
}

# Never distribute these, even if a smoke run left them next to the launcher.
$forbidden = @(
    'cameras.properties',
    'tmp',
    'data',
    'recordings',
    'snapshots',
    'logs'
)
$forbiddenPatterns = @('*.db', '*.db-wal', '*.db-shm', '*.mkv', '*.mp4', '*.log')

$stageRoot = Join-Path $root 'dist\zip-stage'
$stage = Join-Path $stageRoot 'CamWatchman'
$zip = Join-Path $root "dist\CamWatchman-$Version-win64.zip"
Remove-Item -Recurse -Force $stageRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null

Write-Output '[zip] copying app-image (excluding user data) ...'
$exclude = @()
foreach ($name in $forbidden) { $exclude += (Join-Path $image $name) }
Get-ChildItem -Path $image -Force | Where-Object { $exclude -notcontains $_.FullName } | ForEach-Object {
    if ($_.PSIsContainer) {
        Copy-Item $_.FullName $stage -Recurse -Force
    } else {
        Copy-Item $_.FullName $stage -Force
    }
}

# Belt and braces: drop forbidden leftovers from the staged copy and fail if anything survived.
foreach ($name in $forbidden) {
    Remove-Item -Recurse -Force (Join-Path $stage $name) -ErrorAction SilentlyContinue
}
foreach ($pattern in $forbiddenPatterns) {
    Get-ChildItem -Path $stage -Recurse -File -Filter $pattern -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
}
$leaks = @(Get-ChildItem -Path $stage -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -eq 'cameras.properties' -or
    $_.FullName -match '\\tmp\\|\\recordings\\|\\snapshots\\|\\logs\\' -or
    $_.Extension -in @('.mkv', '.mp4', '.db', '.log')
})
if ($leaks.Count -gt 0) {
    $leaks | ForEach-Object { Write-Output "[zip] LEAK: $($_.FullName)" }
    throw 'user data found in the staged app-image; refusing to package'
}

$install = @'
# Cam Watchman - per-user install (no admin, no MSI).
# Copies the program to %LOCALAPPDATA%\Programs\CamWatchman and adds a Start Menu shortcut.
# This script never touches user data: recordings, snapshots, logs and the database live in
# %LOCALAPPDATA%\CamWatchman and stay there across install, upgrade and uninstall.

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
# This script ships inside the app folder, so the launcher is next to it.
$source = $here
if (-not (Test-Path (Join-Path $source 'CamWatchman.exe'))) {
    $nested = Join-Path $here 'CamWatchman'
    if (Test-Path (Join-Path $nested 'CamWatchman.exe')) { $source = $nested }
}
$target = Join-Path $env:LOCALAPPDATA 'Programs\CamWatchman'

if (-not (Test-Path (Join-Path $source 'CamWatchman.exe'))) {
    throw "CamWatchman.exe not found next to this script: $source"
}

New-Item -ItemType Directory -Force $target | Out-Null
# First run only: create a harmless setup placeholder. Never replace an existing camera config.
$dataDir = Join-Path $env:LOCALAPPDATA 'CamWatchman'
New-Item -ItemType Directory -Force $dataDir | Out-Null
$config = Join-Path $dataDir 'cameras.properties'
if (-not (Test-Path $config)) {
    $initialConfig = @(
        '# First run: open Manage Cameras and replace this setup placeholder.'
        'cameras.1.id=1'
        'cameras.1.name=Setup required - add your camera'
        'cameras.1.url=rtsp://127.0.0.1:8554/SETUP_REQUIRED'
        'cameras.1.substream_url='
        'cameras.1.record=false'
        'cameras.1.ai=false'
        'base.data.dir='
        'recording.segment.seconds=15'
        'retention.days=7'
        'retention.size.gb=40'
        'retention.min.free.gb=10'
        'ai.rate.fps=1'
    )
    Set-Content -Path $config -Value $initialConfig -Encoding ascii
}
# Safe merge copy: replace program files, never delete unrelated files in the target folder.
Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force

$exe = Join-Path $target 'CamWatchman.exe'
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
New-Item -ItemType Directory -Force $startMenu | Out-Null
$link = Join-Path $startMenu 'Cam Watchman.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($link)
$shortcut.TargetPath = $exe
$shortcut.WorkingDirectory = $target
$shortcut.Description = 'Cam Watchman - CCTV wall'
$shortcut.Save()

Write-Output "installed to $target"
Write-Output "start it from the Start Menu (Cam Watchman) or: `"$exe`""
Write-Output "uninstall: run uninstall.ps1 from the same folder as this script"
'@
Set-Content (Join-Path $stage 'install.ps1') $install -Encoding ascii

$uninstall = @'
# Cam Watchman - per-user uninstall.
# Removes the installed program files and the Start Menu shortcut only.
# User data (%LOCALAPPDATA%\CamWatchman: database, recordings, snapshots, logs) is NOT deleted.
# Delete that folder yourself only if you want to discard all recorded footage and history.

$ErrorActionPreference = 'Stop'
$target = Join-Path $env:LOCALAPPDATA 'Programs\CamWatchman'
$link = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Cam Watchman.lnk'

if (Test-Path $link) {
    Remove-Item $link -Force
    Write-Output "removed shortcut: $link"
}
if (Test-Path $target) {
    Remove-Item -Recurse -Force $target
    Write-Output "removed program files: $target"
} else {
    Write-Output "program folder not found: $target"
}
Write-Output "user data kept at: $(Join-Path $env:LOCALAPPDATA 'CamWatchman')"
'@
Set-Content (Join-Path $stage 'uninstall.ps1') $uninstall -Encoding ascii

Write-Output '[zip] compressing ...'
Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal -Force
$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
$sha = (Get-FileHash $zip -Algorithm SHA256).Hash
Write-Output "[zip] $zip ($size MB)"
Write-Output "[zip] sha256: $sha"
Write-Output '[zip] user install: extract, then run .\CamWatchman\install.ps1 (no admin)'

if (-not $KeepStage) {
    Remove-Item -Recurse -Force $stageRoot -ErrorAction SilentlyContinue
}
