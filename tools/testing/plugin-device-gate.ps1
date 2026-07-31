[CmdletBinding()]
param(
    [string]$AdbPath = "",
    [string]$Serial = "",
    [string]$ArtifactsDirectory = "",
    [switch]$SkipBuild,
    [switch]$SkipFullSuite,
    [switch]$KeepTestApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
. (Join-Path $PSScriptRoot "plugin-device-gate-result.ps1")
$TestPackage = "com.wuxianggujun.tinaide.core.plugin.test"
$TestRunner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$RuntimeTestClass = "com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeIsolationInstrumentedTest"
$PersistenceTestClass = "com.wuxianggujun.tinaide.plugin.script.PluginQuarantinePersistenceInstrumentedTest"
$PersistenceJournalTest = "$PersistenceTestClass#residualJournal_quarantinesPluginAcrossHostRecreation"
$ForceStopTest = "$PersistenceTestClass#forceStopRelaunchPhase_persistsQuarantineAcrossRealProcessRestart"
$RelaunchPhaseArgument = "tina.plugin.relaunch.phase"
$RuntimeExpectedTestCount = 6
$TestApk = Join-Path $RepositoryRoot "core/plugin/build/outputs/apk/androidTest/debug/plugin-debug-androidTest.apk"
$requestedArtifactsDirectory = if ([string]::IsNullOrWhiteSpace($ArtifactsDirectory)) {
    Join-Path $RepositoryRoot "artifacts/plugin-device-gate"
} elseif ([System.IO.Path]::IsPathRooted($ArtifactsDirectory)) {
    $ArtifactsDirectory
} else {
    Join-Path $RepositoryRoot $ArtifactsDirectory
}
New-Item -ItemType Directory -Force -Path $requestedArtifactsDirectory | Out-Null
$ArtifactsRoot = (Resolve-Path -LiteralPath $requestedArtifactsDirectory).Path
$InstrumentationArtifacts = Join-Path $ArtifactsRoot "instrumentation"
New-Item -ItemType Directory -Force -Path $InstrumentationArtifacts | Out-Null
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$InstrumentationResults = [System.Collections.Generic.List[string]]::new()

function Write-Section([string]$Title) {
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Yellow
}

function Write-Utf8Lines {
    param(
        [string]$Path,
        [AllowEmptyCollection()]
        [string[]]$Lines = @()
    )

    $parentDirectory = Split-Path -Parent $Path
    if ($parentDirectory) {
        New-Item -ItemType Directory -Force -Path $parentDirectory | Out-Null
    }
    [System.IO.File]::WriteAllLines($Path, [string[]]@($Lines), $script:Utf8NoBom)
}

function Resolve-Adb([string]$Requested) {
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        if (Test-Path -LiteralPath $Requested -PathType Leaf) {
            return (Resolve-Path -LiteralPath $Requested).Path
        }
        $requestedCommand = Get-Command $Requested -ErrorAction SilentlyContinue
        if ($requestedCommand) {
            return $requestedCommand.Source
        }
        throw "ADB not found: $Requested"
    }

    $candidates = @(
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe" }),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools/adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools/adb.exe" }),
        "/opt/android-sdk/platform-tools/adb"
    ) | Where-Object { $_ }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        return $adbCommand.Source
    }
    throw "ADB not found. Pass -AdbPath or configure ANDROID_HOME/ANDROID_SDK_ROOT."
}

function Invoke-CapturedProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [switch]$AllowFailure,
        [switch]$Quiet
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    & $FilePath @Arguments 2>&1 | ForEach-Object {
        $line = $_.ToString()
        $lines.Add($line)
        if (-not $Quiet) {
            Write-Host $line
        }
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "Command failed with exit code ${exitCode}: $FilePath $($Arguments -join ' ')"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = $lines.ToArray()
    }
}

function Resolve-DeviceSerial {
    param(
        [string]$Adb,
        [string]$RequestedSerial
    )

    $result = Invoke-CapturedProcess -FilePath $Adb -Arguments @("devices")
    $devices = @(
        $result.Lines |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "\sdevice$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )
    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        if ($RequestedSerial -notin $devices) {
            throw "Requested device is not online: $RequestedSerial"
        }
        return $RequestedSerial
    }
    if ($devices.Count -eq 0) {
        throw "No online ADB device is connected."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple ADB devices are online. Pass -Serial. Devices: $($devices -join ', ')"
    }
    return $devices[0]
}

function Get-AdbArguments {
    param(
        [string]$Device,
        [string[]]$CommandArguments
    )

    return @("-s", $Device) + $CommandArguments
}

function Get-DeviceProperty {
    param(
        [string]$Adb,
        [string]$Device,
        [string]$Property
    )

    $result = Invoke-CapturedProcess -FilePath $Adb `
        -Arguments (Get-AdbArguments -Device $Device -CommandArguments @("shell", "getprop", $Property)) `
        -AllowFailure `
        -Quiet
    if ($result.ExitCode -ne 0) {
        return "<unavailable>"
    }
    $value = ($result.Lines -join "`n").Trim()
    return $(if ($value) { $value } else { "<empty>" })
}

function Save-DeviceInformation {
    param(
        [string]$Adb,
        [string]$Device,
        [string]$Destination
    )

    $adbVersion = Invoke-CapturedProcess -FilePath $Adb -Arguments @("version") -AllowFailure -Quiet
    $lines = @(
        "CapturedAtUtc=$([DateTimeOffset]::UtcNow.ToString('O'))",
        "Serial=$Device",
        "Fingerprint=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.build.fingerprint')",
        "AndroidRelease=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.build.version.release')",
        "ApiLevel=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.build.version.sdk')",
        "PrimaryAbi=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.product.cpu.abi')",
        "AbiList=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.product.cpu.abilist')",
        "Manufacturer=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.product.manufacturer')",
        "Model=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.product.model')",
        "SecurityPatch=$(Get-DeviceProperty -Adb $Adb -Device $Device -Property 'ro.build.version.security_patch')",
        "",
        "ADB version:",
        $adbVersion.Lines
    )
    Write-Utf8Lines -Path $Destination -Lines $lines
}

function Invoke-Instrumentation {
    param(
        [string]$Adb,
        [string]$Device,
        [string]$TestSelector,
        [int]$ExpectedTestCount,
        [string]$LogFileName,
        [hashtable]$RunnerArguments = @{}
    )

    if ($ExpectedTestCount -le 0) {
        throw "ExpectedTestCount must be greater than zero: $TestSelector"
    }
    Write-Host "[RUN] $TestSelector" -ForegroundColor Cyan
    $instrumentArguments = @("shell", "am", "instrument", "-w", "-r", "-e", "class", $TestSelector)
    foreach ($key in ($RunnerArguments.Keys | Sort-Object)) {
        $instrumentArguments += @("-e", $key, [string]$RunnerArguments[$key])
    }
    $instrumentArguments += $TestRunner
    $result = Invoke-CapturedProcess -FilePath $Adb `
        -Arguments (Get-AdbArguments -Device $Device -CommandArguments $instrumentArguments) `
        -AllowFailure
    $output = $result.Lines -join "`n"
    $logPath = Join-Path $script:InstrumentationArtifacts $LogFileName
    Write-Utf8Lines -Path $logPath -Lines $result.Lines

    try {
        $executedTestCount = Assert-PluginInstrumentationResult `
            -Output $output `
            -ProcessExitCode $result.ExitCode `
            -ExpectedTestCount $ExpectedTestCount
    } catch {
        $reason = $_.Exception.Message
        $script:InstrumentationResults.Add(
            "FAIL`t$TestSelector`t$reason`t$logPath"
        )
        throw "Instrumentation gate rejected ${TestSelector}: $reason. Raw output: $logPath"
    }
    $script:InstrumentationResults.Add("PASS`t$TestSelector`t$executedTestCount test(s)`t$logPath")
    Write-Host "[PASS] $TestSelector" -ForegroundColor Green
}

function Stop-TestPackage {
    param(
        [string]$Adb,
        [string]$Device
    )

    Invoke-CapturedProcess -FilePath $Adb `
        -Arguments (Get-AdbArguments -Device $Device -CommandArguments @("shell", "am", "force-stop", $TestPackage)) |
        Out-Null
}

$isWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$gradle = if ($isWindows) {
    Join-Path $RepositoryRoot "gradlew.bat"
} else {
    Join-Path $RepositoryRoot "gradlew"
}
$adb = $null
$device = $null
$installed = $false
$gatePassed = $false
$gateFailure = $null
$deviceInfoPath = Join-Path $ArtifactsRoot "device-info.txt"
$logcatPath = Join-Path $ArtifactsRoot "device-logcat.txt"
$instrumentationSummaryPath = Join-Path $ArtifactsRoot "instrumentation-summary.txt"
$verdictPath = Join-Path $ArtifactsRoot "verdict.txt"
Write-Utf8Lines -Path $deviceInfoPath -Lines @(
    "CapturedAtUtc=$([DateTimeOffset]::UtcNow.ToString('O'))",
    "Serial=<not resolved>"
)

try {
    $adb = Resolve-Adb -Requested $AdbPath
    $device = Resolve-DeviceSerial -Adb $adb -RequestedSerial $Serial

    Write-Section "Plugin device gate"
    Write-Host "Repository: $RepositoryRoot"
    Write-Host "ADB: $adb"
    Write-Host "Device: $device"
    Write-Host "Artifacts: $ArtifactsRoot"
    Save-DeviceInformation -Adb $adb -Device $device -Destination $deviceInfoPath
    Invoke-CapturedProcess -FilePath $adb `
        -Arguments (Get-AdbArguments -Device $device -CommandArguments @("logcat", "-c")) `
        -AllowFailure `
        -Quiet |
        Out-Null

    if (-not $SkipBuild) {
        Write-Section "Build instrumentation APK"
        Invoke-CapturedProcess -FilePath $gradle `
            -Arguments @(":core:plugin:assembleDebugAndroidTest", "--no-daemon", "--console=plain") |
            Out-Null
    }
    if (-not (Test-Path -LiteralPath $TestApk -PathType Leaf)) {
        throw "Instrumentation APK not found: $TestApk"
    }

    Write-Section "Install instrumentation APK"
    Stop-TestPackage -Adb $adb -Device $device
    Invoke-CapturedProcess -FilePath $adb `
        -Arguments (Get-AdbArguments -Device $device -CommandArguments @("uninstall", $TestPackage)) `
        -AllowFailure |
        Out-Null
    Invoke-CapturedProcess -FilePath $adb `
        -Arguments (Get-AdbArguments -Device $device -CommandArguments @("install", "-t", $TestApk)) |
        Out-Null
    $installed = $true

    if (-not $SkipFullSuite) {
        Write-Section "Isolation and quarantine suites"
        Invoke-Instrumentation -Adb $adb `
            -Device $device `
            -TestSelector $RuntimeTestClass `
            -ExpectedTestCount $RuntimeExpectedTestCount `
            -LogFileName "runtime-isolation.log"
        Invoke-Instrumentation -Adb $adb `
            -Device $device `
            -TestSelector $PersistenceJournalTest `
            -ExpectedTestCount 1 `
            -LogFileName "quarantine-persistence.log"
    }

    Write-Section "Force-stop and relaunch persistence"
    Invoke-Instrumentation -Adb $adb `
        -Device $device `
        -TestSelector $ForceStopTest `
        -ExpectedTestCount 1 `
        -LogFileName "force-stop-prepare.log" `
        -RunnerArguments @{ $RelaunchPhaseArgument = "prepare" }
    Stop-TestPackage -Adb $adb -Device $device
    Start-Sleep -Milliseconds 500
    $pidResult = Invoke-CapturedProcess -FilePath $adb `
        -Arguments (Get-AdbArguments -Device $device -CommandArguments @("shell", "pidof", $TestPackage)) `
        -AllowFailure
    if (($pidResult.Lines -join "").Trim()) {
        throw "Test package is still running after force-stop: $($pidResult.Lines -join ' ')"
    }
    Invoke-Instrumentation -Adb $adb `
        -Device $device `
        -TestSelector $ForceStopTest `
        -ExpectedTestCount 1 `
        -LogFileName "force-stop-verify.log" `
        -RunnerArguments @{ $RelaunchPhaseArgument = "verify" }

    Write-Section "Verdict"
    Write-Host "Plugin device stability gate passed." -ForegroundColor Green
    $gatePassed = $true
} catch {
    $gateFailure = $_.Exception.ToString()
    throw
} finally {
    if ($adb -and $device) {
        try {
            $logcatResult = Invoke-CapturedProcess -FilePath $adb `
                -Arguments (Get-AdbArguments -Device $device -CommandArguments @("logcat", "-d", "-v", "threadtime")) `
                -AllowFailure `
                -Quiet
            Write-Utf8Lines -Path $logcatPath -Lines $logcatResult.Lines
        } catch {
            Write-Utf8Lines -Path $logcatPath -Lines @(
                "Unable to capture device logcat.",
                $_.Exception.ToString()
            )
        }
    } else {
        Write-Utf8Lines -Path $logcatPath -Lines @("Device was not resolved; logcat was not captured.")
    }

    Write-Section "Cleanup"
    if ($installed) {
        try {
            Stop-TestPackage -Adb $adb -Device $device
        } catch {
            Write-Warning "Unable to force-stop test package: $($_.Exception.Message)"
        }
        if (-not $KeepTestApp) {
            try {
                Invoke-CapturedProcess -FilePath $adb `
                    -Arguments (Get-AdbArguments -Device $device -CommandArguments @("uninstall", $TestPackage)) `
                    -AllowFailure |
                    Out-Null
            } catch {
                Write-Warning "Unable to uninstall test package: $($_.Exception.Message)"
            }
        }
    }
    $instrumentationSummary = [System.Collections.Generic.List[string]]::new()
    $instrumentationSummary.Add("Status`tSelector`tDetails`tRawLog")
    foreach ($resultLine in $InstrumentationResults) {
        $instrumentationSummary.Add($resultLine)
    }
    Write-Utf8Lines -Path $instrumentationSummaryPath -Lines $instrumentationSummary.ToArray()

    $verdictLines = [System.Collections.Generic.List[string]]::new()
    $verdictLines.Add("CompletedAtUtc=$([DateTimeOffset]::UtcNow.ToString('O'))")
    $verdictLines.Add("Status=$(if ($gatePassed) { 'PASS' } else { 'FAIL' })")
    $verdictLines.Add("Device=$(if ($device) { $device } else { '<not resolved>' })")
    $verdictLines.Add("SkipFullSuite=$([bool]$SkipFullSuite)")
    if ($gateFailure) {
        $verdictLines.Add("")
        $verdictLines.Add("Failure:")
        $verdictLines.Add($gateFailure)
    }
    Write-Utf8Lines -Path $verdictPath -Lines $verdictLines.ToArray()
}
