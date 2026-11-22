# Starts the bridge (javaw, no window) and the Cloudflare tunnel in the background.
# Settings come from a KEY=VALUE file (default: %LOCALAPPDATA%\claude-local-bridge\bridge.env),
# so no secret has to be typed into a window. Logs go to <data dir>\logs.
#
#   start-bridge.ps1              start with the default settings file
#   start-bridge.ps1 -NoTunnel    start only the bridge
param(
    [string]$EnvFile = (Join-Path $env:LOCALAPPDATA 'claude-local-bridge\bridge.env'),
    [switch]$NoTunnel
)
$ErrorActionPreference = 'Stop'
$home_ = Split-Path -Parent $EnvFile

function Read-EnvFile([string]$path) {
    $vars = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { throw "Bad line in ${path}: $t" }
        $vars[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $vars
}

if (-not (Test-Path -LiteralPath $EnvFile)) { throw "Settings file not found: $EnvFile (run install-autostart.ps1 first)" }
$cfg = Read-EnvFile $EnvFile
foreach ($k in 'BRIDGE_HMAC_SECRET', 'BRIDGE_JAR', 'BRIDGE_DATA_DIR') {
    if (-not $cfg[$k]) { throw "$k is empty in $EnvFile" }
}
if ($cfg['BRIDGE_TELEGRAM_ENABLED'] -ne 'false' -and (-not $cfg['BRIDGE_TELEGRAM_TOKEN'] -or -not $cfg['BRIDGE_TELEGRAM_CHAT_ID'])) {
    throw "Fill BRIDGE_TELEGRAM_TOKEN and BRIDGE_TELEGRAM_CHAT_ID in $EnvFile"
}

$port = if ($cfg['BRIDGE_PORT']) { [int]$cfg['BRIDGE_PORT'] } else { 8765 }
if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    Write-Output "Port $port is already in use: the bridge (or something else) is running. Nothing started."
    exit 0
}

$data = $cfg['BRIDGE_DATA_DIR']
$logs = Join-Path $data 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null

# Only the variables the bridge reads go into its environment.
foreach ($k in $cfg.Keys) {
    if ($k -like 'BRIDGE_*' -and $k -notin 'BRIDGE_JAR', 'BRIDGE_DATA_DIR', 'BRIDGE_ARGS') {
        Set-Item -Path "Env:$k" -Value $cfg[$k]
    }
}
# The javaw on PATH is often Oracle's javapath launcher, which starts the real JVM as a
# child; use the JDK's own javaw so the recorded pid is the bridge itself.
$javaHome = if ($cfg['JAVA_HOME']) { $cfg['JAVA_HOME'] } else {
    # through cmd: in Windows PowerShell, stderr of a native command + ErrorAction Stop would throw
    ((cmd.exe /c 'java -XshowSettings:properties -version 2>&1' | Select-String 'java.home =') -replace '.*java.home = ', '').Trim()
}
$javaw = Join-Path $javaHome 'bin\javaw.exe'
if (-not (Test-Path -LiteralPath $javaw)) { throw "javaw.exe not found under $javaHome (set JAVA_HOME in $EnvFile)" }
$tmp = Join-Path $data 'tmp'
New-Item -ItemType Directory -Force $tmp | Out-Null
# The JDK's internal loopback pipe is an AF_UNIX socket in the temp dir; a long %TEMP% path
# can break it ("Unable to establish loopback connection"), so give it a short one.
$args_ = @("-Djdk.net.unixdomain.tmpdir=$tmp", '-jar', $cfg['BRIDGE_JAR'], "--logging.file.name=$logs\bridge.log")
if ($cfg['BRIDGE_ARGS']) { $args_ += ($cfg['BRIDGE_ARGS'] -split '\s+(?=--)') }
$bridge = Start-Process -FilePath $javaw -ArgumentList ($args_ | ForEach-Object { '"' + $_ + '"' }) `
    -WorkingDirectory $data -PassThru
$pids = @("bridge=$($bridge.Id)")

if (-not $NoTunnel) {
    $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
    if (-not $cf) { throw 'cloudflared not found on PATH' }
    $cfConfig = if ($cfg['CLOUDFLARED_CONFIG']) { $cfg['CLOUDFLARED_CONFIG'] } else { Join-Path $env:USERPROFILE '.cloudflared\config.yml' }
    if (Get-Process cloudflared -ErrorAction SilentlyContinue) {
        Write-Output 'cloudflared is already running; not starting a second one.'
    } else {
        $tunnel = Start-Process -FilePath $cf.Source -WindowStyle Hidden -PassThru -ArgumentList @(
            'tunnel', '--config', "`"$cfConfig`"", '--logfile', "`"$logs\cloudflared.log`"", 'run')
        $pids += "cloudflared=$($tunnel.Id)"
    }
}
Set-Content -LiteralPath (Join-Path $home_ 'running.pids') -Value $pids -Encoding ASCII

# Wait until the API answers (401 without a signature means it is up).
$deadline = (Get-Date).AddSeconds(60)
do {
    Start-Sleep -Seconds 2
    try { Invoke-WebRequest "http://127.0.0.1:$port/tasks/ping" -UseBasicParsing -TimeoutSec 3 | Out-Null; $up = $true }
    catch { $up = [bool]$_.Exception.Response }
    if ($bridge.HasExited) { throw "The bridge exited at start; see $logs\bridge.log" }
} until ($up -or (Get-Date) -gt $deadline)
if ($up) { Write-Output "Bridge is up on port $port (pid $($bridge.Id)); logs in $logs" }
else { Write-Output "The bridge did not answer within 60 s; see $logs\bridge.log" }
