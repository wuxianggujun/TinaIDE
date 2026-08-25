$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "plugin-device-gate-result.ps1")

function Assert-Rejected {
    param(
        [string]$Name,
        [string]$Output,
        [int]$ProcessExitCode,
        [int]$ExpectedTestCount,
        [string]$ExpectedMessage
    )

    try {
        Assert-PluginInstrumentationResult `
            -Output $Output `
            -ProcessExitCode $ProcessExitCode `
            -ExpectedTestCount $ExpectedTestCount |
            Out-Null
    } catch {
        if ($_.Exception.Message -notmatch [regex]::Escape($ExpectedMessage)) {
            throw "${Name}: unexpected rejection: $($_.Exception.Message)"
        }
        return
    }
    throw "${Name}: result should have been rejected."
}

$successfulOutput = @"
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_CODE: -1
OK (2 tests)
"@
$count = Assert-PluginInstrumentationResult -Output $successfulOutput -ProcessExitCode 0 -ExpectedTestCount 2
if ($count -ne 2) {
    throw "Successful result returned an unexpected count: $count"
}

Assert-Rejected `
    -Name "zero tests" `
    -Output "INSTRUMENTATION_CODE: -1`nOK (0 tests)" `
    -ProcessExitCode 0 `
    -ExpectedTestCount 1 `
    -ExpectedMessage "runner executed zero tests"

Assert-Rejected `
    -Name "assumption skip" `
    -Output "INSTRUMENTATION_STATUS_CODE: -4`nINSTRUMENTATION_CODE: -1`nOK (1 test)" `
    -ProcessExitCode 0 `
    -ExpectedTestCount 1 `
    -ExpectedMessage "assumption-skipped"

Assert-Rejected `
    -Name "ignored test" `
    -Output "INSTRUMENTATION_STATUS_CODE: -3`nINSTRUMENTATION_CODE: -1`nOK (1 test)" `
    -ProcessExitCode 0 `
    -ExpectedTestCount 1 `
    -ExpectedMessage "ignored/assumption-skipped"

Assert-Rejected `
    -Name "test count mismatch" `
    -Output "INSTRUMENTATION_STATUS_CODE: 0`nINSTRUMENTATION_CODE: -1`nOK (2 tests)" `
    -ProcessExitCode 0 `
    -ExpectedTestCount 1 `
    -ExpectedMessage "expected 1"

Assert-Rejected `
    -Name "runner failure" `
    -Output "INSTRUMENTATION_STATUS_CODE: -2`nFAILURES!!!" `
    -ProcessExitCode 0 `
    -ExpectedTestCount 1 `
    -ExpectedMessage "failure"

Assert-Rejected `
    -Name "adb failure" `
    -Output "" `
    -ProcessExitCode 1 `
    -ExpectedTestCount 1 `
    -ExpectedMessage "adb exited with code 1"

Write-Host "Plugin device gate result parser tests passed."
