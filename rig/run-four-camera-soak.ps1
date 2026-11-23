[CmdletBinding()]
param(
    [int]$Minutes = 60,
    [int]$SampleSeconds = 60,
    [string]$DataDir = (Join-Path $env:LOCALAPPDATA 'CamWatchman'),
    [string]$OutputFile = (Join-Path $PSScriptRoot 'run\four-camera-soak.csv'),
    [string]$DbStatsScript = (Join-Path $PSScriptRoot 'soak-db-stats.py')
)

$ErrorActionPreference = 'Stop'
if ($Minutes -lt 1 -or $SampleSeconds -lt 5) { throw 'Minutes must be >= 1 and SampleSeconds >= 5.' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputFile) | Out-Null
$log = Join-Path $DataDir 'logs\cam-watchman.log'
$db = Join-Path $DataDir 'cam-watchman.db'
if (-not (Test-Path $db)) { throw "Cam Watchman database not found: $db" }

$header = 'timestamp,app_pid,app_working_set_mb,app_private_mb,app_cpu_seconds,system_free_gb,system_cpu_percent,recording_rows,error_lines'
Set-Content -Path $OutputFile -Value $header -Encoding UTF8
$runStart = Get-Date
$deadline = $runStart.AddMinutes($Minutes)
$sample = 0
Write-Host "[soak] monitoring for $Minutes minute(s), sample every $SampleSeconds seconds"
Write-Host "[soak] output: $OutputFile"

while ((Get-Date) -lt $deadline) {
    $sample++
    # Support both the Maven-launched Java process and the packaged jpackage launcher.
    # In the packaged build the visible JavaFX window belongs to the CamWatchman.exe child.
    $p = Get-Process -Name 'CamWatchman' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if (-not $p) {
        $appProc = Get-CimInstance Win32_Process -Filter "name='java.exe'" |
            Where-Object { $_.CommandLine -like '*com.cctv.app.Main*' } | Select-Object -First 1
        if ($appProc) {
            $p = Get-Process -Id $appProc.ProcessId -ErrorAction SilentlyContinue
        }
    }
    $appPid = ''
    $workingSet = ''
    $privateBytes = ''
    $cpuSeconds = ''
    if ($p) {
        $appPid = $p.Id
        $workingSet = [math]::Round($p.WorkingSet64 / 1MB, 1)
        $privateBytes = [math]::Round($p.PrivateMemorySize64 / 1MB, 1)
        $cpuSeconds = [math]::Round($p.CPU, 1)
    }
    $os = Get-CimInstance Win32_OperatingSystem
    $freeGb = [math]::Round($os.FreePhysicalMemory / 1MB, 2)
    $cpuPercent = ''
    try { $cpuPercent = [math]::Round((Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 1).CounterSamples[0].CookedValue, 1) } catch { }
    $recordingRows = ''
    try { $recordingRows = (& py $DbStatsScript $db 2>&1 | Out-String).Trim() } catch { $recordingRows = "QUERY_ERROR:$($_.Exception.Message)" }
    $errorLines = 0
    if (Test-Path $log) {
        $errorLines = (Select-String -Path $log -Pattern ' ERROR |Exception|queue full|failed to write|database is locked' | Where-Object {
            if ($_.Line -match '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})') {
                [datetime]::ParseExact($matches[1], 'yyyy-MM-dd HH:mm:ss', $null) -ge $runStart
            } else { $false }
        } | Measure-Object).Count
    }
    $row = [pscustomobject]@{
        timestamp = (Get-Date).ToString('s')
        app_pid = $appPid
        app_working_set_mb = $workingSet
        app_private_mb = $privateBytes
        app_cpu_seconds = $cpuSeconds
        system_free_gb = $freeGb
        system_cpu_percent = $cpuPercent
        recording_rows = $recordingRows
        error_lines = $errorLines
    }
    $row | ConvertTo-Csv -NoTypeInformation | Select-Object -Skip 1 | Add-Content -Path $OutputFile -Encoding UTF8
    Write-Host ("[soak] sample {0}: app={1} RAM={2}MB free={3}GB CPU={4}%" -f $sample,$appPid,$workingSet,$freeGb,$cpuPercent)
    if (-not $appPid) {
        Write-Warning '[soak] Cam Watchman process is no longer running; stopping monitor.'
        break
    }
    Start-Sleep -Seconds $SampleSeconds
}

Write-Host "[soak] finished with $sample sample(s). App was not stopped."
Write-Host "[soak] results: $OutputFile"
