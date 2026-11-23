#requires -Version 5.1
<#
.SYNOPSIS
    Builds the self-contained Cam Watchman app-image with jpackage (Phase 8e).

.DESCRIPTION
    Produces dist\CamWatchman\ containing the launcher, the bundled Java runtime, the app jar with
    its dependencies in app\lib, models\yolo11n.onnx and cameras.properties.example.

    Produces the app-image only. Build the per-user Windows installer separately with
    scripts/package-installer.ps1 (Inno Setup) after reviewing its licensing terms.

.EXAMPLE
    .\scripts\package-appimage.ps1
    .\scripts\package-appimage.ps1 -Clean
#>
[CmdletBinding()]
param(
    [string]$Version = "0.2.0",
    [string]$DistDir = "dist",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $root
try {
    if ($Clean) {
        Remove-Item -Recurse -Force $DistDir, "target\package" -ErrorAction SilentlyContinue
    }

    $jarName = "cctv-dashboard-$Version-SNAPSHOT.jar"
    $stageInput = "target\package\input"
    if (Test-Path $stageInput) { Remove-Item -Recurse -Force $stageInput }
    New-Item -ItemType Directory -Force $stageInput | Out-Null

    Write-Host "[package] mvnw package"
    & .\mvnw.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "maven package failed (exit $LASTEXITCODE)" }
    if (!(Test-Path "target\$jarName")) { throw "packaged application jar was not created: target\$jarName" }

    Write-Host "[package] copy runtime dependencies"
    & .\mvnw.cmd -q dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$stageInput\lib"
    if ($LASTEXITCODE -ne 0) { throw "dependency copy failed (exit $LASTEXITCODE)" }

    Copy-Item "target\$jarName" $stageInput -Force
    Copy-Item "cameras.properties.example" "$stageInput\cameras.properties.example" -Force

    $deps = @(Get-ChildItem "$stageInput\lib\*.jar" -ErrorAction SilentlyContinue)
    if ($deps.Count -eq 0) { throw "no runtime dependencies were copied to $stageInput\lib" }
    Write-Host "[package] $($deps.Count) dependency jar(s) staged"

    if (Test-Path (Join-Path $DistDir "CamWatchman")) {
        Remove-Item -Recurse -Force (Join-Path $DistDir "CamWatchman")
    }

    Write-Host "[package] jpackage --type app-image"
    & jpackage --type app-image --name CamWatchman --dest $DistDir --input $stageInput `
        --main-jar $jarName --main-class com.cctv.app.Main `
        --app-version $Version --vendor "Cam Watchman" `
        --description "4-camera CCTV wall with independent recording and AI" `
        --java-options "-Xms128m" --java-options "-Xmx1200m" `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

    $app = Join-Path $DistDir "CamWatchman"
    New-Item -ItemType Directory -Force (Join-Path $app "models") | Out-Null
    Copy-Item "models\yolo11n.onnx" (Join-Path $app "models") -Force
    Copy-Item "cameras.properties.example" $app -Force

    # FFmpeg is a required application dependency, not an optional machine-wide prerequisite.
    # Prefer an explicitly supplied binary, then the repository's tools folder, then PATH.
    $ffmpegSource = $env:CAMWATCHMAN_FFMPEG
    if ([string]::IsNullOrWhiteSpace($ffmpegSource)) {
        $candidate = Join-Path $root "tools\ffmpeg\ffmpeg.exe"
        if (Test-Path $candidate) { $ffmpegSource = $candidate }
    }
    if ([string]::IsNullOrWhiteSpace($ffmpegSource)) {
        $onPath = Get-Command ffmpeg -ErrorAction SilentlyContinue
        if ($onPath) { $ffmpegSource = $onPath.Source }
    }
    if ([string]::IsNullOrWhiteSpace($ffmpegSource) -or -not (Test-Path $ffmpegSource -PathType Leaf)) {
        throw "FFmpeg is required for camera streaming. Set CAMWATCHMAN_FFMPEG to a redistributable ffmpeg.exe or place it at tools\ffmpeg\ffmpeg.exe. Refusing to build an incomplete customer app."
    }
    $ffmpegSource = (Resolve-Path $ffmpegSource).Path
    $ffmpegDir = Split-Path $ffmpegSource -Parent
    $bundleDir = Join-Path $app "ffmpeg"
    New-Item -ItemType Directory -Force $bundleDir | Out-Null
    Copy-Item $ffmpegSource (Join-Path $bundleDir "ffmpeg.exe") -Force
    # Shared Windows FFmpeg builds need their adjacent runtime DLLs. Static builds simply have none.
    Get-ChildItem $ffmpegDir -Filter "*.dll" -File -ErrorAction SilentlyContinue |
        Copy-Item -Destination $bundleDir -Force
    $bundledFfmpeg = Join-Path $bundleDir "ffmpeg.exe"
    if (-not (Test-Path $bundledFfmpeg -PathType Leaf)) { throw "FFmpeg bundle is incomplete: ffmpeg.exe missing" }
    Write-Host "[package] required FFmpeg bundled from $ffmpegSource"

    # Smoke runs inside the app-image write cameras.properties and tmp\ data next to the launcher.
    # A distributable image must never contain those, so scrub user artifacts after every build.
    $userArtifacts = @("cameras.properties", "tmp", "data", "recordings", "snapshots", "logs")
    foreach ($artifact in $userArtifacts) {
        Remove-Item -Recurse -Force (Join-Path $app $artifact) -ErrorAction SilentlyContinue
    }
    Get-ChildItem -Path $app -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Extension -in @(".db", ".db-wal", ".db-shm", ".mkv", ".mp4", ".log")
    } | Remove-Item -Force -ErrorAction SilentlyContinue
    $residue = @(Get-ChildItem -Path $app -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -eq "cameras.properties" -or $_.Extension -in @(".db", ".db-wal", ".db-shm", ".mkv", ".mp4", ".log")
    })
    if ($residue.Count -gt 0) {
        $residue | ForEach-Object { Write-Host "[package] LEAK: $($_.FullName)" }
        throw "user data survived the app-image scrub; refusing to publish the image"
    }
    Write-Host "[package] user artifacts scrubbed from the app-image"

    $exe = Join-Path $app "CamWatchman.exe"
    $sizeMb = [math]::Round(((Get-ChildItem $app -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host "[package] app-image: $app ($sizeMb MB)"
    Write-Host "[package] launcher:  $exe"

    Write-Host "[package] app-image ready; use scripts/package-installer.ps1 for the Inno Setup installer"
    Write-Host "[package] app-image is an internal test build until licensing, signing and clean-PC checks pass"
} finally {
    Pop-Location
}
