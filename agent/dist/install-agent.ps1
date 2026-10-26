# Installs the Orbit agent for the current Windows user and starts it at sign-in.
# Usage:  powershell -ExecutionPolicy Bypass -File install-agent.ps1 [-Jar path\to\orbit-agent.jar]
#         powershell -ExecutionPolicy Bypass -File install-agent.ps1 -Uninstall
param(
    [string]$Jar = (Join-Path $PSScriptRoot 'orbit-agent.jar'),
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'

$share = Join-Path $env:LOCALAPPDATA 'Orbit'
$installedJar = Join-Path $share 'orbit-agent.jar'
$logging = Join-Path $share 'logging.properties'
$shortcut = Join-Path ([Environment]::GetFolderPath('Startup')) 'Orbit agent.lnk'

function Stop-Agent {
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*$installedJar*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}

if ($Uninstall) {
    Stop-Agent
    Remove-Item -Force -ErrorAction SilentlyContinue $shortcut, $installedJar, $logging
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $share 'runtime')
    Write-Host "Orbit agent removed. Its settings and logs stay in $env:APPDATA\orbit and $share."
    exit 0
}

# Bundles from agentBundle carry their own Java runtime; the plain bundle needs Java 25 on the system.
$bundledRuntime = Join-Path $PSScriptRoot 'runtime'
$bundled = Test-Path (Join-Path $bundledRuntime 'bin\javaw.exe')
if ($bundled) {
    $javaw = Join-Path $share 'runtime\bin\javaw.exe'
} else {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Error 'Java 25 or newer is required; install it first (e.g. Eclipse Temurin 25) and open a new terminal.'
    }
    # java -version writes to stderr, which Windows PowerShell 5.1 turns into a terminating error under 'Stop'.
    $ErrorActionPreference = 'Continue'
    $versionLine = (& $java.Source -version 2>&1 | Select-Object -First 1).ToString().Trim()
    $ErrorActionPreference = 'Stop'
    if ($versionLine -notmatch '"(\d+)' -or [int]$Matches[1] -lt 25) {
        Write-Error "Java 25 or newer is required (found: $versionLine)."
    }
    $javaw = Join-Path (Split-Path $java.Source) 'javaw.exe'
    if (-not (Test-Path $javaw)) {
        Write-Error "javaw.exe not found next to $($java.Source)."
    }
}

$moonlight = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA) |
    Where-Object { $_ } |
    ForEach-Object { Join-Path $_ 'Moonlight Game Streaming\Moonlight.exe' } |
    Where-Object { Test-Path $_ }
if (-not $moonlight -and -not (Get-Command Moonlight.exe -ErrorAction SilentlyContinue)) {
    Write-Warning "Moonlight not found; install it, or set moonlightCommand in $env:APPDATA\orbit\agent.json."
}

New-Item -ItemType Directory -Force $share | Out-Null
Stop-Agent
Copy-Item -Force $Jar $installedJar
$installedRuntime = Join-Path $share 'runtime'
if (Test-Path $installedRuntime) {
    Remove-Item -Recurse -Force $installedRuntime
}
if ($bundled) {
    Copy-Item -Recurse $bundledRuntime $installedRuntime
}

# javaw has no console, so the log goes to a file. java.util.logging wants forward slashes here.
$logPattern = (Join-Path $share 'agent%g.log').Replace('\', '/')
@"
handlers = java.util.logging.FileHandler
java.util.logging.FileHandler.pattern = $logPattern
java.util.logging.FileHandler.limit = 1000000
java.util.logging.FileHandler.count = 3
java.util.logging.FileHandler.append = true
java.util.logging.FileHandler.encoding = UTF-8
java.util.logging.FileHandler.formatter = java.util.logging.SimpleFormatter
java.util.logging.SimpleFormatter.format = %1`$tF %1`$tT %4`$s %3`$s: %5`$s%6`$s%n
"@ | Set-Content -Encoding ASCII $logging

$arguments = "`"-Djava.util.logging.config.file=$logging`" -jar `"$installedJar`""
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $javaw
$link.Arguments = $arguments
$link.WorkingDirectory = $share
$link.Description = 'Orbit agent'
$link.Save()

Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $share

Write-Host 'Orbit agent installed and started; it starts again at each sign-in.'
Write-Host 'Approve this client in the Orbit window on the Sunshine host.'
Write-Host "Logs: $share\agent0.log"
