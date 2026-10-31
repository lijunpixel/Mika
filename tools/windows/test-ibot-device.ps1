# Pixel/API 37 acceptance. Each Compose case uses its own instrumentation process because
# the inherited Compose test clock can re-enter layout between cases. No package data is cleared.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$PackageName = 'com.wangzan101.mika.multiai.dev',
    [string]$EvidenceDirectory = (Join-Path $PSScriptRoot '../../dist/device-tests'),
    [switch]$Wake
)
$ErrorActionPreference = 'Stop'
if ($PackageName -ne 'com.wangzan101.mika.multiai.dev') {
    throw 'This acceptance script only targets the isolated ibot development package.'
}
$state = (& $Adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw 'Requested device is not online.' }
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$cases = [ordered]@{
    'no-key' = 'com.wangzan101.mika.ui.CollaborationScreenTest#unconfiguredRealTaskCannotStart'
    'four-modes' = 'com.wangzan101.mika.ui.CollaborationScreenTest#allFourMockModesStartAndComplete'
    'cancel' = 'com.wangzan101.mika.ui.CollaborationScreenTest#activeSlowMockTaskCanBeCancelled'
    'mcp' = 'com.wangzan101.mika.ui.CollaborationMcpTest'
}
$testCount = 0
foreach ($entry in $cases.GetEnumerator()) {
    if ($Wake) {
        & $Adb -s $Serial shell input keyevent 224
        if ($LASTEXITCODE -ne 0) { throw 'Device wake failed; manually unlock the device.' }
    }
    $result = & $Adb -s $Serial shell am instrument -w -e class $entry.Value "$PackageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
    $instrumentExit = $LASTEXITCODE
    $output = $result | Out-String
    $output | Set-Content -LiteralPath (Join-Path $EvidenceDirectory ($entry.Key + '.txt')) -Encoding UTF8
    # ADB itself may exit zero after an instrumentation crash or assertion failure.
    $passed = [regex]::Match($output, '(?m)^OK \(([1-9]\d*) tests?\)\s*$')
    if ($instrumentExit -ne 0 -or -not $passed.Success -or
        $output -match 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED') {
        Write-Output $output
        throw "Device acceptance failed: $($entry.Key)"
    }
    $testCount += [int]$passed.Groups[1].Value
    Write-Output "$($entry.Key): PASS"
}
Write-Output "Pixel acceptance: PASS ($testCount tests, isolated processes; Mock only, no real provider calls)."
