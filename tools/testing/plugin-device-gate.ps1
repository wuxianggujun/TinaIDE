[CmdletBinding()]
param(
    [string]$AdbPath = "",
    [string]$Serial = "",
    [switch]$SkipBuild,
    [switch]$SkipFullSuite,
    [switch]$KeepTestApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$TestPackage = "com.wuxianggujun.tinaide.core.plugin.test"
$TestRunner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$RuntimeTestClass = "com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeIsolationInstrumentedTest"
$PersistenceTestClass = "com.wuxianggujun.tinaide.plugin.script.PluginQuarantinePersistenceInstrumentedTest"
$ForceStopTest = "$PersistenceTestClass#forceStopRelaunchPhase_persistsQuarantineAcrossRealProcessRestart"
$RelaunchPhaseArgument = "tina.plugin.relaunch.phase"
$TestApk = Join-Path $RepositoryRoot "core/plugin/build/outputs/apk/androidTest/debug/plugin-debug-androidTest.apk"

function Write-Section([string]$Title) {
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Yellow
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
        [switch]$AllowFailure
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    & $FilePath @Arguments 2>&1 | ForEach-Object {
        $line = $_.ToString()
        $lines.Add($line)
        Write-Host $line
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

function Invoke-Instrumentation {
    param(
        [string]$Adb,
        [string]$Device,
        [string]$TestSelector,
        [hashtable]$RunnerArguments = @{}
    )

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
    $failed = $result.ExitCode -ne 0 -or
        $output -match "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=" -or
        $output -notmatch "OK \([0-9]+ tests?\)"
    if ($failed) {
        throw "Instrumentation failed: $TestSelector"
    }
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

$adb = Resolve-Adb -Requested $AdbPath
$device = Resolve-DeviceSerial -Adb $adb -RequestedSerial $Serial
$isWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$gradle = if ($isWindows) {
    Join-Path $RepositoryRoot "gradlew.bat"
} else {
    Join-Path $RepositoryRoot "gradlew"
}
$installed = $false

try {
    Write-Section "Plugin device gate"
    Write-Host "Repository: $RepositoryRoot"
    Write-Host "ADB: $adb"
    Write-Host "Device: $device"

    if (-not $SkipBuild) {
        Write-Section "Build instrumentation APK"
        Invoke-CapturedProcess -FilePath $gradle `
            -Arguments @(":core:plugin:assembleDebugAndroidTest", "--console=plain") |
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
        Invoke-Instrumentation -Adb $adb -Device $device -TestSelector $RuntimeTestClass
        Invoke-Instrumentation -Adb $adb -Device $device -TestSelector $PersistenceTestClass
    }

    Write-Section "Force-stop and relaunch persistence"
    Invoke-Instrumentation -Adb $adb `
        -Device $device `
        -TestSelector $ForceStopTest `
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
        -RunnerArguments @{ $RelaunchPhaseArgument = "verify" }

    Write-Section "Verdict"
    Write-Host "Plugin device stability gate passed." -ForegroundColor Green
} finally {
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
    if (-not $SkipBuild -and (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        try {
            Invoke-CapturedProcess -FilePath $gradle -Arguments @("--stop") -AllowFailure | Out-Null
        } catch {
            Write-Warning "Unable to stop Gradle daemons: $($_.Exception.Message)"
        }
    }
}
