function Assert-PluginInstrumentationResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Output,
        [int]$ProcessExitCode,
        [int]$ExpectedTestCount
    )

    if ($ExpectedTestCount -le 0) {
        throw "ExpectedTestCount must be greater than zero."
    }

    $problems = [System.Collections.Generic.List[string]]::new()
    if ($ProcessExitCode -ne 0) {
        $problems.Add("adb exited with code $ProcessExitCode")
    }
    if ($Output -match "(?im)^FAILURES!!!|^INSTRUMENTATION_FAILED|^INSTRUMENTATION_ABORTED|Process crashed|shortMsg=") {
        $problems.Add("runner reported a failure or crash")
    }

    $failureStatuses = [regex]::Matches(
        $Output,
        "(?im)^INSTRUMENTATION_STATUS_CODE:\s*-(?:1|2)\s*$"
    )
    if ($failureStatuses.Count -gt 0) {
        $problems.Add("runner reported $($failureStatuses.Count) error/failure status code(s)")
    }

    $skippedStatuses = [regex]::Matches(
        $Output,
        "(?im)^INSTRUMENTATION_STATUS_CODE:\s*-(?:3|4)\s*$"
    )
    if ($skippedStatuses.Count -gt 0) {
        $problems.Add("runner reported $($skippedStatuses.Count) ignored/assumption-skipped test(s)")
    }

    $okMatches = [regex]::Matches($Output, "(?im)^OK \(([0-9]+) tests?\)\s*$")
    $executedTestCount = $null
    if ($okMatches.Count -eq 0) {
        $problems.Add("runner did not emit a successful test summary")
    } else {
        $executedTestCount = [int]$okMatches[$okMatches.Count - 1].Groups[1].Value
        if ($executedTestCount -eq 0) {
            $problems.Add("runner executed zero tests")
        } elseif ($executedTestCount -ne $ExpectedTestCount) {
            $problems.Add("runner executed $executedTestCount test(s), expected $ExpectedTestCount")
        }
    }

    if ($problems.Count -gt 0) {
        throw "Instrumentation result rejected: $($problems -join '; ')"
    }
    return $executedTestCount
}
