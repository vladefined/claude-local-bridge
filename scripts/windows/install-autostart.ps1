# Sets up autostart for the current user (no admin rights needed):
#   - %LOCALAPPDATA%\claude-local-bridge\bridge.env: settings, with a new random HMAC secret
#     (created only if it does not exist yet; never overwritten)
#   - a copy of the jar there, so rebuilding the project does not touch the running bridge
#   - a shortcut in the Startup folder that runs start-bridge.ps1 hidden at logon
# Run it again after `mvn package` to update the jar (stop the bridge first).
#
#   install-autostart.ps1 [-Jar <path>] [-DataDir <dir>] [-NoShortcut]
param(
    [string]$Jar = (Join-Path $PSScriptRoot '..\..\target\claude-local-bridge-0.1.0.jar'),
    # working directory of the bridge: the SQLite DB and outputs\ live here
    [string]$DataDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [switch]$NoShortcut
)
$ErrorActionPreference = 'Stop'
$home_ = Join-Path $env:LOCALAPPDATA 'claude-local-bridge'
New-Item -ItemType Directory -Force $home_ | Out-Null

$jarPath = (Resolve-Path $Jar).Path
$jarCopy = Join-Path $home_ 'claude-local-bridge.jar'
Copy-Item -LiteralPath $jarPath -Destination $jarCopy -Force
Write-Output "jar copied to $jarCopy"

# scripts are copied too, so the shortcut does not depend on the checkout
foreach ($s in 'start-bridge.ps1', 'stop-bridge.ps1') {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $s) -Destination (Join-Path $home_ $s) -Force
}

$envFile = Join-Path $home_ 'bridge.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    @"
# claude-local-bridge settings, read by start-bridge.ps1. KEY=VALUE, no quotes.
# Keep this file private: it holds the HMAC secret and the Telegram bot token.

# Shared with the cloud side (its BRIDGE_HMAC_SECRET must be the same value).
BRIDGE_HMAC_SECRET=$secret

# Fill these two (bot token from @BotFather, your numeric user id from @userinfobot).
BRIDGE_TELEGRAM_TOKEN=
BRIDGE_TELEGRAM_CHAT_ID=

BRIDGE_LANGUAGE=ru
BRIDGE_JAR=$jarCopy
BRIDGE_DATA_DIR=$DataDir

# Folder requests from the cloud only inside these directories (comma-separated).
# Empty: anywhere the built-in deny rules allow.
BRIDGE_ACCESS_ROOTS=

# Optional extra arguments for the bridge, e.g. other folders:
# BRIDGE_ARGS=--bridge.folders.pibench.path=E:\pibench
"@ | Set-Content -LiteralPath $envFile -Encoding UTF8
    Write-Output "settings written to $envFile (new HMAC secret generated)"
} else {
    Write-Output "settings kept: $envFile"
}

if (-not $NoShortcut) {
    $startup = [Environment]::GetFolderPath('Startup')
    $lnk = Join-Path $startup 'claude-local-bridge.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $sc = $shell.CreateShortcut($lnk)
    $sc.TargetPath = Join-Path $env:SystemRoot 'System32\conhost.exe'
    $sc.Arguments = '--headless powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' +
            (Join-Path $home_ 'start-bridge.ps1') + '"'
    $sc.WorkingDirectory = $home_
    $sc.Description = 'Start claude-local-bridge and its Cloudflare tunnel'
    $sc.Save()
    Write-Output "autostart shortcut: $lnk"
}
