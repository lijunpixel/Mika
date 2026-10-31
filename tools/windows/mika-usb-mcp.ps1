#Requires -Version 5.1
<#
.SYNOPSIS
  Connect Mika's USB-only MCP server to a Windows machine safely.
.DESCRIPTION
  Requires Android Platform Tools (adb), USB debugging authorization and an
  installed Mika build that supports connect-computer.sh.
  Connection secrets stay in memory unless -ShowConfig is explicitly supplied.
.EXAMPLE
  .\mika-usb-mcp.ps1 -Action Connect
.EXAMPLE
  .\mika-usb-mcp.ps1 -Action Connect -Serial YOUR_DEVICE_SERIAL -LocalPort 18765 -ShowConfig
.EXAMPLE
  .\mika-usb-mcp.ps1 -Action Close
#>
[CmdletBinding()]
param(
    [ValidateSet('Connect', 'Status', 'RefreshForward', 'Close')]
    [string]$Action = 'Connect',
    [string]$Serial,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName = 'com.wangzan101.mika',
    [ValidateRange(1024, 65535)]
    [int]$LocalPort = 8765,
    [switch]$ShowConfig,
    [switch]$CheckHealth
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$connectionFile = "/sdcard/Android/data/$PackageName/files/mcp-usb.json"
$connectionScript = "/sdcard/Android/data/$PackageName/files/connect-computer.sh"

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [Parameter(Mandatory=$true)][string]$Step
    )
    $argv = @('-s', $Serial) + $Arguments
    # Capture output because the connection bootstrap can print a bearer token.
    $output = & $script:AdbExecutable @argv 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw (("ADB step '{0}' failed (exit {1}). Check the device and Mika. " +
                "The command output is hidden to protect connection tokens.") -f $Step, $exitCode)
    }
    return (($output | Out-String).Trim())
}

function Get-Info {
    $text = Invoke-Adb -Arguments @('shell', 'cat', $connectionFile) -Step 'read connection metadata'
    try {
        $info = $text | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw 'Mika connection metadata is missing or invalid. Open Mika once, then run -Action Connect.'
    }
    if (-not $info -or -not $info.token -or -not $info.port -or -not $info.boot) {
        throw 'Mika connection metadata is incomplete. Run -Action Connect again.'
    }
    if ([string]$info.token -notmatch '^[A-Za-z0-9_-]{32,}$') {
        throw 'Mika returned an unexpected token format.'
    }
    $remotePort = [int]$info.port
    if ($remotePort -lt 1 -or $remotePort -gt 65535) {
        throw 'Mika returned an invalid remote port.'
    }
    # Never follow a URL supplied by a device. Construct our own loopback URL.
    $boot = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'global', 'boot_count') -Step 'check boot ID').Trim()
    if ([string]$info.boot -ne $boot) {
        throw 'Connection metadata belongs to another phone boot. Run -Action Connect again.'
    }
    return $info
}

function Find-Forward {
    $rows = Invoke-Adb -Arguments @('forward', '--list') -Step 'list ADB forwards'
    foreach ($row in ($rows -split "`r?`n")) {
        if ($row -match '^\s*(\S+)\s+tcp:(\d+)\s+tcp:(\d+)\s*$') {
            if ([int]$Matches[2] -eq $LocalPort) {
                return @{
                    Serial = [string]$Matches[1]
                    RemotePort = [int]$Matches[3]
                }
            }
        }
    }
    return $null
}

function Ensure-Forward {
    param([int]$RemotePort)
    $existing = Find-Forward
    if ($null -ne $existing) {
        if ($existing.Serial -ne $Serial -or $existing.RemotePort -ne $RemotePort) {
            throw "Local TCP port $LocalPort already forwards to another destination. Choose a different -LocalPort."
        }
        return
    }
    # --no-rebind avoids silently taking over an existing device's mapping.
    $null = Invoke-Adb -Arguments @('forward', '--no-rebind', "tcp:$LocalPort", "tcp:$RemotePort") -Step 'create USB-only forward'
    $actual = Find-Forward
    if ($null -eq $actual -or $actual.Serial -ne $Serial -or $actual.RemotePort -ne $RemotePort) {
        throw 'ADB did not confirm the requested forward.'
    }
}

function Remove-OwnedForward {
    param([int]$RemotePort)
    $existing = Find-Forward
    if ($null -eq $existing) { return }
    if ($existing.Serial -ne $Serial -or $existing.RemotePort -ne $RemotePort) {
        throw "Port $LocalPort is owned by another mapping. Refusing to remove it."
    }
    $null = Invoke-Adb -Arguments @('forward', '--remove', "tcp:$LocalPort") -Step 'remove ADB forward'
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adb) {
    throw 'adb not found in PATH. Install Android SDK Platform-Tools before running this script.'
}
$script:AdbExecutable = $adb.Source

# Device discovery must NOT use a previously cached serial.
$rawDevices = & $script:AdbExecutable devices 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'adb devices failed. Check Android Platform-Tools and the USB cable.'
}
$devices = @(
    foreach ($line in $rawDevices) {
        if ([string]$line -match '^(\S+)\s+(device|unauthorized|offline)\s*$') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
)
if ($Serial) {
    if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid ADB device serial.' }
    $device = @($devices | Where-Object { $_.Serial -ceq $Serial })
    if ($device.Count -ne 1) { throw "Device '$Serial' not found. Check adb devices." }
} else {
    if ($devices.Count -ne 1) {
        throw 'Connect exactly one Android device, or specify -Serial explicitly.'
    }
    $device = @($devices)
    $Serial = $device[0].Serial
}
if ($device[0].State -ne 'device') {
    throw "Device '$Serial' is $($device[0].State). Unlock your phone and authorize USB debugging."
}

if ($Action -eq 'Close') {
    # Read the phone's actual port *before* disabling the service and deleting its metadata.
    # If the app is already off, fall back to the upstream default only for an owned mapping.
    $closeInfo = $null
    try { $closeInfo = Get-Info } catch { }
    $remoteToRemove = if ($null -ne $closeInfo) { [int]$closeInfo.port } else { 8765 }
    # Disabling on the phone invalidates the token. Removing only a local forward is not enough.
    $null = Invoke-Adb -Arguments @('shell', 'sh', $connectionScript, 'off') -Step 'disable USB MCP'
    Remove-OwnedForward -RemotePort $remoteToRemove
    Write-Output "Mika USB MCP disabled on $Serial; owned local forwarding removed."
    exit 0
}

if ($Action -eq 'Connect') {
    # No stdout logging: upstream's script prints the secret token in its JSON.
    $null = Invoke-Adb -Arguments @('shell', 'sh', $connectionScript) -Step 'enable USB MCP'
}

$info = Get-Info
$remotePort = [int]$info.port
if ($Action -eq 'Connect' -or $Action -eq 'RefreshForward') {
    Ensure-Forward -RemotePort $remotePort
}

$existing = Find-Forward
$forwardOk = ($null -ne $existing -and $existing.Serial -eq $Serial -and $existing.RemotePort -eq $remotePort)
$uri = "http://127.0.0.1:$LocalPort/mcp"

Write-Output ("Device: {0}`nMika MCP: {1}`nADB forward: {2}" -f $Serial, $uri, $(if ($forwardOk) {'ready'} else {'missing'}))

if ($CheckHealth) {
    if (-not $forwardOk) { throw 'Cannot probe MCP: forwarding is missing.' }
    $payload = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mika-windows-usb-check","version":"1"}}}'
    try {
        $response = Invoke-WebRequest -Uri $uri -Method Post -UseBasicParsing `
            -ContentType 'application/json' `
            -Headers @{ Authorization = "Bearer $($info.token)"; Accept = 'application/json, text/event-stream' } `
            -Body $payload -TimeoutSec 10
        Write-Output "MCP initialize HTTP: $([int]$response.StatusCode)"
    } catch {
        throw 'MCP initialize check failed. Inspect the Mika notification and ADB forwarding; token details are hidden.'
    }
}

if ($ShowConfig) {
    if (-not $forwardOk) { throw 'Refusing to show MCP config without a working forward mapping.' }
    # Print secrets only by explicit opt-in, never write them to disk.
    Write-Warning 'The JSON below contains a bearer token. Do not paste it into public logs or issues.'
    $config = @{
        mcpServers = @{
            mika = @{
                type = 'http'
                url = $uri
                headers = @{ Authorization = "Bearer $($info.token)" }
            }
        }
    }
    Write-Output ($config | ConvertTo-Json -Depth 6)
}
