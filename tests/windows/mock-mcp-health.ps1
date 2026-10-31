# Hermetic HTTP double for startup rotation and bounded failure; never opens a socket.
param([string]$Helper, [ValidateSet('rotate','fail','port')][string]$Scenario)
$ErrorActionPreference = 'Stop'
# Separate child-process state avoids the helper's own script-scoped retry variables.
$global:ibotMockHealthState = @{ Attempts = 0; Scenario = $Scenario }
function Invoke-WebRequest {
    param($Uri, $Method, [switch]$UseBasicParsing, $ContentType, $Headers, $Body, $TimeoutSec)
    $global:ibotMockHealthState.Attempts++
    if ($global:ibotMockHealthState.Scenario -eq 'fail') { throw 'Mock service unavailable' }
    if ($global:ibotMockHealthState.Attempts -eq 1) {
        Set-Content -LiteralPath (Join-Path $env:MIKA_FAKE_STATE_DIR 'token-rotated') -Value '1'
        if ($global:ibotMockHealthState.Scenario -eq 'port') { $env:MIKA_FAKE_PORT = '19334' }
        throw 'Mock old token rejected'
    }
    if ($Headers.Authorization -ne 'Bearer TEST_TOKEN_NOT_A_SECRET_ABCDEFGHIJKLMNOPQRSTUVWXYZ_123456NEW') {
        throw 'Mock requires freshly loaded metadata'
    }
    [pscustomobject]@{ StatusCode = 200 }
}
try { & $Helper -Action Connect -CheckHealth }
finally {
    Set-Content -LiteralPath (Join-Path $env:MIKA_FAKE_STATE_DIR 'health-attempts') -Value $global:ibotMockHealthState.Attempts
}
