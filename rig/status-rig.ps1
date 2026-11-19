# Show the rig's RTSP paths and - the number that matters for the architecture - how many
# readers each path has. Two readers on one path means the one-connection rule is broken.
[CmdletBinding()]
param([string]$Api = 'http://127.0.0.1:9997')
Set-StrictMode -Version Latest

try {
    $paths = (Invoke-RestMethod -Uri "$Api/v3/paths/list" -TimeoutSec 5).items
} catch {
    Write-Host "[rig] MediaMTX API not reachable at $Api - is the rig running?"
    exit 1
}

$paths |
    Sort-Object name |
    Select-Object name, ready, readers, writers,
        @{n='source';e={$_.source.type}},
        @{n='tracks';e={($_.tracks -join ',')}} |
    Format-Table -AutoSize | Out-String | Write-Host

$names = @('cam1_main') + (1..4 | ForEach-Object { "cam${_}_sub" })
$missing = $names | Where-Object { $_ -notin $paths.name }
if ($missing) { Write-Host "[rig] MISSING: $($missing -join ', ')" } else { Write-Host '[rig] all 5 expected paths present.' }
