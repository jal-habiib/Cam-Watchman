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
    [string]$Version = "0.1.0",
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
        --description "4-camera CCTV wall (Camera 1 full pipeline)" `
        --java-options "-Xms128m" --java-options "-Xmx1200m" `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

    $app = Join-Path $DistDir "CamWatchman"
    New-Item -ItemType Directory -Force (Join-Path $app "models") | Out-Null
    Copy-Item "models\yolo11n.onnx" (Join-Path $app "models") -Force
    Copy-Item "cameras.properties.example" $app -Force

    # Optional: bundle a static ffmpeg.exe so the image runs without a machine-wide install.
    $ffmpegSource = Join-Path $root "tools\ffmpeg\ffmpeg.exe"
    if (-not (Test-Path $ffmpegSource)) {
        $onPath = Get-Command ffmpeg -ErrorAction SilentlyContinue
        if ($onPath) { $ffmpegSource = $onPath.Source }
    }
    if (Test-Path $ffmpegSource) {
        New-Item -ItemType Directory -Force (Join-Path $app "ffmpeg") | Out-Null
        Copy-Item $ffmpegSource (Join-Path $app "ffmpeg\ffmpeg.exe") -Force
        Write-Host "[package] bundled ffmpeg from $ffmpegSource"
    } else {
        Write-Host "[package] WARNING: no ffmpeg found; put a static tools\ffmpeg\ffmpeg.exe here to bundle it"
    }

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
