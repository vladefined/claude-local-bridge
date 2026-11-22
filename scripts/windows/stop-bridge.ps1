# Stops what start-bridge.ps1 started (the pids it wrote next to the settings file).
param([string]$EnvFile = (Join-Path $env:LOCALAPPDATA 'claude-local-bridge\bridge.env'))
$pidFile = Join-Path (Split-Path -Parent $EnvFile) 'running.pids'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output 'Nothing recorded as running.'; exit 0 }
foreach ($line in Get-Content -LiteralPath $pidFile) {
    $name, $id = $line -split '=', 2
    $p = Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue
    # only stop it if the pid still belongs to the same program (pids get reused)
    if ($p -and (($name -eq 'bridge' -and $p.ProcessName -like 'java*') -or ($name -eq 'cloudflared' -and $p.ProcessName -eq 'cloudflared'))) {
        # whole tree: a running task's claude process goes with the bridge
        & taskkill.exe /PID $p.Id /T /F | Out-Null
        Write-Output "stopped $name (pid $($p.Id))"
    }
}
Remove-Item -LiteralPath $pidFile -Confirm:$false
