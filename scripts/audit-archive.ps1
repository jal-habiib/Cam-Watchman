#requires -Version 5.1
<#
.SYNOPSIS
    Audits a distributable Cam Watchman zip: no user data, no secrets, exact listing and SHA-256.

.DESCRIPTION
    Repeatable release check for the WiX-free distribution path. It opens the zip, verifies that no
    user-specific file (camera config, databases, logs, recordings, snapshots) is present, scans
    text files for embedded credentials, prints the file count/size/SHA-256 and fails with exit 1 on
    any violation.

.EXAMPLE
    pwsh scripts/audit-archive.ps1 -Archive dist\CamWatchman-0.1.0-win64.zip
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [switch]$List
)

$ErrorActionPreference = 'Stop'
$path = (Resolve-Path $Archive).Path
$sha = (Get-FileHash $path -Algorithm SHA256).Hash
$sizeMb = [math]::Round((Get-Item $path).Length / 1MB, 1)
$entries = @(Get-Item $path).Length
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($path)
$names = @($zip.Entries | ForEach-Object { $_.FullName })
$zip.Dispose()

$forbidden = @(
    '(^|/)cameras\.properties$',
    '(^|/)tmp/',
    '(^|/)data/',
    '(^|/)recordings/',
    '(^|/)snapshots/',
    '(^|/)logs/',
    '\.db$',
    '\.db-wal$',
    '\.db-shm$',
    '\.mkv$',
    '\.mp4$',
    '\.log$'
)
$leaks = @($names | Where-Object {
    $name = $_
    ($forbidden | Where-Object { $name -match $_ }).Count -gt 0
})

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("cam-watchman-audit-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $temp | Out-Null
try {
    Expand-Archive -Path $path -DestinationPath $temp -Force
    $textFiles = @(Get-ChildItem -Path $temp -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Extension -in @('.properties', '.example', '.ps1', '.json', '.txt', '.md', '.cfg', '.ini', '.yml', '.yaml') -and
        $_.FullName -notmatch '\\runtime\\' -and
        $_.Length -lt 2MB
    })
    $secretHits = @()
    foreach ($file in $textFiles) {
        $content = Get-Content -Path $file.FullName -Raw -ErrorAction SilentlyContinue
        if ($null -eq $content) { continue }
        # Placeholder credentials (USER:PASS, user:pass, <user>, ***) are documentation, not secrets.
        $content = $content -replace '(?i)(user|<user>|\*\*\*):(pass|<pass>|password|\*\*\*)', ''
        if ($content -match 'rtsp://[^/\s"''@]+:[^/\s"''@]+@' -or
            $content -match '(?i)(password|passwd|pwd|token|secret)\s*[:=]\s*["'']?[^\s"''#]{6,}') {
            $secretHits += $file.FullName.Substring($temp.Length).TrimStart('\')
        }
    }
} finally {
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
}

Write-Output "[audit] archive: $path"
Write-Output "[audit] size:    $sizeMb MB"
Write-Output "[audit] files:   $($names.Count)"
Write-Output "[audit] sha256:  $sha"
Write-Output "[audit] text files scanned for secrets: $($textFiles.Count)"
if ($List) {
    $names | Sort-Object | ForEach-Object { Write-Output "  $_" }
}
if ($leaks.Count -gt 0) {
    $leaks | ForEach-Object { Write-Output "[audit] FORBIDDEN: $_" }
}
if ($secretHits.Count -gt 0) {
    $secretHits | ForEach-Object { Write-Output "[audit] SECRET: $_" }
}
if ($leaks.Count -gt 0 -or $secretHits.Count -gt 0) {
    throw "[audit] FAILED: $($leaks.Count) forbidden path(s), $($secretHits.Count) secret hit(s)"
}
if (-not ($names | Where-Object { $_ -match 'CamWatchman\.exe$' })) {
    throw "[audit] FAILED: CamWatchman.exe is missing from the archive"
}
if (-not ($names | Where-Object { $_ -match 'cameras\.properties\.example$' })) {
    throw "[audit] FAILED: cameras.properties.example is missing from the archive"
}
Write-Output '[audit] PASS: no user data, no credentials, launcher and example config present'
