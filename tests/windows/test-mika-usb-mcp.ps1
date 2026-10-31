#Requires -Version 5.1
# Hermetic smoke tests for the Windows USB MCP helper. No phone, network, or Pester needed.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$target = Join-Path $repo 'tools/windows/mika-usb-mcp.ps1'
$engine = Join-Path $PSHOME $(if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' })
$dir = Join-Path ([IO.Path]::GetTempPath()) ('mika-adb-mock-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$oldPath = $env:PATH
$env:PATH = "$dir;$oldPath"
$env:MIKA_FAKE_STATE_DIR = $dir
$env:MIKA_FAKE_BOOT = '17'
$env:MIKA_FAKE_PORT = '8765'
$mock = Join-Path $PSScriptRoot 'mock-adb.ps1'
# Test-only adb.cmd shadows system adb.exe; command output never reaches a phone.
$cmd = '@echo off' + [Environment]::NewLine + 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $mock + '" %*' + [Environment]::NewLine
Set-Content -LiteralPath (Join-Path $dir 'adb.cmd') -Encoding Ascii -Value $cmd
$token = 'TEST_TOKEN_NOT_A_SECRET_ABCDEFGHIJKLMNOPQRSTUVWXYZ_123456'
$failures = New-Object System.Collections.Generic.List[string]
$passes = 0

function Reset-Fake {
    foreach ($f in 'enabled', 'forward.txt', 'token-rotated', 'health-attempts') {
        Remove-Item -LiteralPath (Join-Path $dir $f) -ErrorAction SilentlyContinue
    }
    $env:MIKA_FAKE_DEVICE_COUNT = '1'
    $env:MIKA_FAKE_AUTH = 'device'
    $env:MIKA_FAKE_PORT = '8765'
    $env:MIKA_FAKE_INFO_BOOT = '17'
}
function Invoke-Helper {
    param([string[]]$Options)
    # Negative tests may emit stderr; capture it rather than turning it into a terminating error.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $engine -NoProfile -ExecutionPolicy Bypass -File $target @Options 2>&1)
        $code = $LASTEXITCODE
        return [pscustomobject]@{ Code = $code; Text = ($output | Out-String) }
    } finally { $ErrorActionPreference = $old }
}
function Assert-Case {
    param([string]$Name, [bool]$Pass)
    if (-not $Pass) { $failures.Add($Name); Write-Host "FAIL $Name"; return }
    $script:passes++
    Write-Host "PASS $Name"
}
try {
    Reset-Fake
    $c = Invoke-Helper -Options @('-Action','Connect')
    Assert-Case 'connect forwards to the device' ($c.Code -eq 0 -and $c.Text -match 'ADB forward: ready' -and (Test-Path (Join-Path $dir 'forward.txt')))
    Assert-Case 'default output never contains the bearer' ($c.Text -notmatch [regex]::Escape($token))
    $c = Invoke-Helper -Options @('-Action','Status')
    Assert-Case 'status keeps the bearer hidden' ($c.Code -eq 0 -and $c.Text -notmatch [regex]::Escape($token))
    $c = Invoke-Helper -Options @('-Action','Status','-ShowConfig')
    Assert-Case 'token is displayed only with explicit ShowConfig' ($c.Code -eq 0 -and $c.Text -match [regex]::Escape($token) -and $c.Text -match 'http://127.0.0.1:8765/mcp' -and $c.Text -notmatch 'evil.example')
    Remove-Item -LiteralPath (Join-Path $dir 'forward.txt')
    $c = Invoke-Helper -Options @('-Action','RefreshForward')
    Assert-Case 'refresh restores the missing forward' ($c.Code -eq 0 -and $c.Text -match 'ADB forward: ready')
    $c = Invoke-Helper -Options @('-Action','Close')
    Assert-Case 'close revokes token and forward' ($c.Code -eq 0 -and -not (Test-Path (Join-Path $dir 'enabled')) -and -not (Test-Path (Join-Path $dir 'forward.txt')))

    Reset-Fake
    $env:MIKA_FAKE_DEVICE_COUNT = '2'
    $c = Invoke-Helper -Options @('-Action','Connect')
    Assert-Case 'multiple devices require explicit serial' ($c.Code -ne 0 -and $c.Text -match 'specify -Serial')
    $c = Invoke-Helper -Options @('-Action','Connect','-Serial','MOCK_SERIAL')
    Assert-Case 'explicit serial picks the intended device' ($c.Code -eq 0)

    Reset-Fake
    $env:MIKA_FAKE_AUTH = 'unauthorized'
    $c = Invoke-Helper -Options @('-Action','Connect')
    Assert-Case 'unauthorized device rejected' ($c.Code -ne 0 -and $c.Text -match 'unauthorized')

    Reset-Fake
    Set-Content -LiteralPath (Join-Path $dir 'forward.txt') -Value 'SECOND_SERIAL tcp:8765 tcp:8765' -Encoding Ascii
    $c = Invoke-Helper -Options @('-Action','Connect')
    $unchanged = Get-Content -LiteralPath (Join-Path $dir 'forward.txt') -Raw
    Assert-Case 'existing forward cannot be hijacked' ($c.Code -ne 0 -and $unchanged -match '^SECOND_SERIAL')

    Reset-Fake
    $env:MIKA_FAKE_PORT = '19333'
    $c = Invoke-Helper -Options @('-Action','Connect','-LocalPort','18765')
    Assert-Case 'nondefault local and remote ports work' ($c.Code -eq 0 -and (Get-Content (Join-Path $dir 'forward.txt') -Raw) -match 'tcp:18765 tcp:19333')
    $c = Invoke-Helper -Options @('-Action','Close','-LocalPort','18765')
    Assert-Case 'close cleans nondefault port mapping' ($c.Code -eq 0 -and -not (Test-Path (Join-Path $dir 'forward.txt')))

    Reset-Fake
    $env:MIKA_FAKE_INFO_BOOT = '16'
    $c = Invoke-Helper -Options @('-Action','Connect')
    Assert-Case 'stale boot metadata rejected' ($c.Code -ne 0 -and $c.Text -match 'another phone boot' -and $c.Text -notmatch [regex]::Escape($token))

    foreach ($scenario in 'rotate', 'fail', 'port') {
        Reset-Fake
        $old = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $output = @(& $engine -NoProfile -File (Join-Path $PSScriptRoot 'mock-mcp-health.ps1') -Helper $target -Scenario $scenario 2>&1)
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $old }
        $attempts = [int](Get-Content -LiteralPath (Join-Path $dir 'health-attempts'))
        $text = $output | Out-String
        $noToken = $text -notmatch [regex]::Escape($token)
        switch ($scenario) {
            'rotate' { Assert-Case 'startup health reloads rotated metadata' ($code -eq 0 -and $attempts -eq 2 -and $text -match 'HTTP: 200' -and $noToken) }
            'fail' { Assert-Case 'startup health stops after three failures' ($code -ne 0 -and $attempts -eq 3 -and $text -match 'initialize check failed' -and $noToken) }
            'port' { Assert-Case 'startup port change never follows another mapping' ($code -ne 0 -and $attempts -eq 1 -and $text -match 'port changed' -and $noToken) }
        }
    }

    Write-Host "RESULT: $passes passed, $($failures.Count) failed"
    if ($failures.Count -gt 0) { exit 1 }
} finally {
    $env:PATH = $oldPath
    foreach ($name in 'MIKA_FAKE_STATE_DIR','MIKA_FAKE_BOOT','MIKA_FAKE_PORT','MIKA_FAKE_AUTH','MIKA_FAKE_DEVICE_COUNT','MIKA_FAKE_INFO_BOOT') {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}

# Last deliberate negative case must not leak its native process exit code to Actions.
exit 0
