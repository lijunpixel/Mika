# Test-only fake ADB. Never talks to an Android device.
$ErrorActionPreference = 'Stop'
$stateDir = $env:MIKA_FAKE_STATE_DIR
if (-not $stateDir -or -not (Test-Path -LiteralPath $stateDir)) { exit 90 }
$enabledFile = Join-Path $stateDir 'enabled'
$forwardFile = Join-Path $stateDir 'forward.txt'
$token = 'TEST_TOKEN_NOT_A_SECRET_ABCDEFGHIJKLMNOPQRSTUVWXYZ_123456'
$port = if ($env:MIKA_FAKE_PORT) { [int]$env:MIKA_FAKE_PORT } else { 8765 }
$boot = if ($env:MIKA_FAKE_BOOT) { $env:MIKA_FAKE_BOOT } else { '17' }
$infoBoot = if ($env:MIKA_FAKE_INFO_BOOT) { $env:MIKA_FAKE_INFO_BOOT } else { $boot }

if ($args.Count -eq 1 -and $args[0] -eq 'devices') {
    Write-Output 'List of devices attached'
    $status = if ($env:MIKA_FAKE_AUTH -eq 'unauthorized') { 'unauthorized' } else { 'device' }
    Write-Output "MOCK_SERIAL $status"
    if ($env:MIKA_FAKE_DEVICE_COUNT -eq '2') { Write-Output 'SECOND_SERIAL device' }
    exit 0
}
if ($args.Count -lt 4 -or $args[0] -ne '-s') { exit 91 }
$serial = $args[1]
if ($serial -notin @('MOCK_SERIAL','SECOND_SERIAL')) { exit 92 }
$op = $args[2]
if ($op -eq 'shell') {
    if ($args[3] -eq 'sh') {
        if ($args.Count -ge 6 -and $args[5] -eq 'off') {
            Remove-Item -LiteralPath $enabledFile -ErrorAction SilentlyContinue
            Write-Output 'off'
        } else {
            Set-Content -LiteralPath $enabledFile -Value '1' -NoNewline -Encoding Ascii
            Write-Output "Computer access active. TEST only: $token"
        }
        exit 0
    }
    if ($args[3] -eq 'cat') {
        if (-not (Test-Path -LiteralPath $enabledFile)) { exit 1 }
        @{token=$token;port=$port;boot=$infoBoot;url='http://evil.example/mcp'} | ConvertTo-Json -Compress
        exit 0
    }
    if ($args[3] -eq 'settings') { Write-Output $boot; exit 0 }
    exit 93
}
if ($op -eq 'forward') {
    if ($args[3] -eq '--list') {
        if (Test-Path -LiteralPath $forwardFile) { Get-Content -LiteralPath $forwardFile }
        exit 0
    }
    if ($args[3] -eq '--remove') {
        if (Test-Path -LiteralPath $forwardFile) { Remove-Item -LiteralPath $forwardFile }
        exit 0
    }
    if ($args[3] -eq '--no-rebind' -and $args.Count -ge 6) {
        if (Test-Path -LiteralPath $forwardFile) { exit 1 }
        Set-Content -LiteralPath $forwardFile -Value "$serial $($args[4]) $($args[5])" -NoNewline -Encoding Ascii
        exit 0
    }
}
exit 94
