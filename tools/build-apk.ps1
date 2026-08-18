# TinaIDE 统一构建脚本
# 用法示例：
#   .\build-apk.ps1                                    # Debug 构建 arm64（默认）
#   .\build-apk.ps1 -Abi x86                           # Debug 构建 x86_64
#   .\build-apk.ps1 -AllAbi                            # Debug 构建所有架构
#   .\build-apk.ps1 -Install                           # Debug 构建 arm64 并安装
#   .\build-apk.ps1 -Variant release -Install          # Release 构建 arm64 并安装
#   .\build-apk.ps1 -Variant release -AllAbi           # Release 多 ABI 构建
#   .\build-apk.ps1 -FullClean -Install                # 完整清理后构建并安装

Param(
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",

    [ValidateSet("arm64", "x86", "all")]
    [string]$Abi = "arm64",   # 指定构建架构：arm64 (arm64-v8a), x86 (x86_64), all (所有架构)
    [switch]$AllAbi,          # 构建所有 ABI（等同于 -Abi all）
    [switch]$Universal,       # 构建包含所有 ABI 的通用 APK（体积更大但兼容所有设备）
    [switch]$FullClean,       # 完整清理后构建
    [switch]$NativeClean,     # 仅清理 Native 构建产物
    [switch]$Install,         # 构建后安装到设备
    [switch]$OpenFolder       # 完成后打开输出目录
)

$ErrorActionPreference = "Stop"

# 全局变量：跟踪安装 Job
$script:installJob = $null

# Ctrl+C 中断处理：清理 adb 进程
$null = Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action {
    if ($script:installJob) {
        Write-Host "`nCleaning up adb installation job..." -ForegroundColor Yellow
        Stop-Job $script:installJob -ErrorAction SilentlyContinue
        Remove-Job $script:installJob -Force -ErrorAction SilentlyContinue
    }
}

# ========================================
# 路径设置
# ========================================

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..")
Set-Location $repoRoot

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  TinaIDE Build Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Variant: $Variant" -ForegroundColor White

# 处理架构参数：-AllAbi 优先级高于 -Abi
if ($AllAbi) {
    $Abi = "all"
}
Write-Host "Architecture: $Abi" -ForegroundColor White
Write-Host "Universal: $Universal" -ForegroundColor White
Write-Host "Project root: $repoRoot" -ForegroundColor DarkGray
Write-Host ""

# ========================================
# 辅助函数
# ========================================

function Get-GradleAbiArguments {
    param([string]$abi)

    switch ($abi) {
        "arm64" { return @("-Ptina.devAbi=arm64") }
        "x86"   { return @("-Ptina.devAbi=x86_64") }
        "all"   { return @("-Ptina.allAbi=true") }
        default { return @() }
    }
}

[string[]]$gradleAbiArgs = @(Get-GradleAbiArguments -abi $Abi)
[string[]]$gradleExecutionArgs = @("--no-daemon", "--console=plain")
if ($Variant -eq "release") {
    # R8 and lint are both heap-intensive. Serializing Release tasks avoids
    # concurrent flavor analysis exhausting the 4 GiB Gradle heap on 16 GiB hosts.
    $gradleExecutionArgs += @("--max-workers=1", "--no-parallel", "--no-watch-fs")
}

function Invoke-GradleTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$WarnOnly
    )
    Write-Host "Executing Gradle task: $Task" -ForegroundColor DarkCyan
    if ($gradleAbiArgs.Count -gt 0) {
        Write-Host "Gradle ABI arguments: $($gradleAbiArgs -join ' ')" -ForegroundColor DarkGray
    }
    Write-Host "Gradle execution arguments: $($gradleExecutionArgs -join ' ')" -ForegroundColor DarkGray
    [string[]]$gradleArgs = @($gradleAbiArgs + $Task + $gradleExecutionArgs)
    & ./gradlew @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        if ($WarnOnly) {
            Write-Host "Gradle task failed (${Task}) but script will continue. See Gradle output above for details." -ForegroundColor Yellow
        } else {
            Write-Host "Gradle task failed: $Task" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    }
}

function Invoke-CompositeGradleTask {
    param(
        [Parameter(Mandatory = $true)][string]$WrapperPath,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$WarnOnly
    )
    Write-Host "Executing composite Gradle task: $Task" -ForegroundColor DarkCyan
    Push-Location $WorkingDirectory
    try {
        & $WrapperPath $Task @gradleExecutionArgs
        if ($LASTEXITCODE -ne 0) {
            if ($WarnOnly) {
                Write-Host "Composite Gradle task failed (${Task}) but script will continue. See Gradle output above for details." -ForegroundColor Yellow
            } else {
                Write-Host "Composite Gradle task failed: $Task" -ForegroundColor Red
                exit $LASTEXITCODE
            }
        }
    } finally {
        Pop-Location
    }
}

function Clean-AllOutputs {
    Write-Host "Performing full Gradle clean (app/build et al)..." -ForegroundColor Cyan
    Invoke-GradleTask -Task "clean" -WarnOnly:$false

    $treeSitterRoot = Join-Path $repoRoot "external\tina-android-tree-sitter"
    $treeSitterWrapper = Join-Path $treeSitterRoot "gradlew.bat"
    if (Test-Path $treeSitterWrapper) {
        Write-Host "Cleaning external tina-android-tree-sitter outputs..." -ForegroundColor Cyan
        Invoke-CompositeGradleTask `
            -WrapperPath $treeSitterWrapper `
            -WorkingDirectory $treeSitterRoot `
            -Task ":android-tree-sitter:clean" `
            -WarnOnly:$false
    }

    $pathsToRemove = @(
        (Join-Path $repoRoot "app/build"),
        (Join-Path $repoRoot "external\tina-android-tree-sitter\android-tree-sitter\build")
    )
    foreach ($path in $pathsToRemove) {
        if (Test-Path $path) {
            Write-Host "Removing $path" -ForegroundColor DarkGray
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
        }
    }
}

function Clean-NativeOutputs {
    Write-Host "Cleaning previous native build outputs..." -ForegroundColor Cyan
    $cleanTasks = @(
        ":app:externalNativeBuildCleanDebug",
        ":app:externalNativeBuildCleanRelease"
    )
    foreach ($task in $cleanTasks) {
        Invoke-GradleTask -Task $task -WarnOnly
    }
    $pathsToRemove = @(
        (Join-Path $repoRoot "app/.cxx"),
        (Join-Path $repoRoot "app/build/intermediates/cmake")
    )
    foreach ($path in $pathsToRemove) {
        if (Test-Path $path) {
            Write-Host "Removing $path" -ForegroundColor DarkGray
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
        }
    }
}

# ========================================
# 签名检查（仅 Release）
# ========================================

if ($Variant -eq "release") {
    Write-Host "Checking release signing configuration..." -ForegroundColor Cyan

    $keystoreProps = Join-Path $repoRoot "keystore.properties"
    if (-not (Test-Path $keystoreProps)) {
        Write-Host "Error: keystore.properties not found" -ForegroundColor Red
        Write-Host "Release builds require signing configuration" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "  ✓ keystore.properties found" -ForegroundColor Green

    $keystoreFile = Join-Path $repoRoot "keystore\release.jks"
    if (-not (Test-Path $keystoreFile)) {
        Write-Host "Error: keystore\release.jks not found" -ForegroundColor Red
        exit 1
    }
    Write-Host "  ✓ keystore file found" -ForegroundColor Green
    Write-Host ""
}

# ========================================
# 清理阶段
# ========================================

if ($FullClean) {
    Clean-AllOutputs
}

if ($NativeClean) {
    Clean-NativeOutputs
}

# ========================================
# 构建阶段
# ========================================

# 如果指定了 -Universal，临时修改 build.gradle.kts 启用 universal APK
if ($Universal) {
    Write-Host "Enabling universal APK generation (includes all ABIs)..." -ForegroundColor Yellow
    $buildGradle = Join-Path $repoRoot "app/build.gradle.kts"
    $buildGradleContent = Get-Content $buildGradle -Raw
    
    # 备份原始配置
    $buildGradleBackup = $buildGradleContent
    
    # 临时修改配置：启用 universal APK
    $buildGradleContent = $buildGradleContent -replace 'isUniversalApk = false', 'isUniversalApk = true'
    Set-Content $buildGradle $buildGradleContent
    
    Write-Host "  ✓ Universal APK enabled" -ForegroundColor Green
}

function Get-GradleTask {
    param([string]$variant, [string]$abi)
    $capitalized = $variant.Substring(0,1).ToUpper() + $variant.Substring(1)

    # 架构映射：用户输入 -> Gradle flavor 名称
    $flavorMap = @{
        "arm64" = "Arm64"
        "x86"   = "X86_64"
        "all"   = "AllAbi"
    }

    if ($abi -eq "all") {
        # 构建所有架构：使用自定义任务 assembleDebugAllAbi / assembleReleaseAllAbi
        return "assemble${capitalized}AllAbi"
    } else {
        # 构建单个架构：assembleArm64Debug / assembleX86_64Release
        $flavorName = $flavorMap[$abi]
        if (-not $flavorName) {
            Write-Host "Error: Unknown architecture '$abi'" -ForegroundColor Red
            Write-Host "Supported architectures: arm64, x86, all" -ForegroundColor Yellow
            exit 1
        }
        return "assemble${flavorName}${capitalized}"
    }
}

$gradleTask = Get-GradleTask -variant $Variant -abi $Abi
Write-Host "Building APK..." -ForegroundColor Cyan
if ($Variant -eq "release") {
    Write-Host "  - Code shrinking enabled (R8)" -ForegroundColor DarkGray
    Write-Host "  - Resource shrinking enabled" -ForegroundColor DarkGray
}
if ($Abi -eq "all") {
    Write-Host "  - Target ABI: arm64-v8a, x86_64 (all)" -ForegroundColor DarkGray
} else {
    $abiDisplayName = if ($Abi -eq "arm64") { "arm64-v8a" } elseif ($Abi -eq "x86") { "x86_64" } else { $Abi }
    Write-Host "  - Target ABI: $abiDisplayName" -ForegroundColor DarkGray
}
Write-Host ""

try {
    Invoke-GradleTask -Task $gradleTask
} finally {
    # 如果启用了 Universal，恢复原始配置
    if ($Universal -and $buildGradleBackup) {
        Set-Content (Join-Path $repoRoot "app/build.gradle.kts") $buildGradleBackup
        Write-Host "Restored original build configuration" -ForegroundColor DarkGray
    }
}

# 查找生成的 APK
# 说明：启用 ABI flavor 后，APK 输出目录形如：
#   app/build/outputs/apk/arm64/release/*.apk
#   app/build/outputs/apk/x86_64/release/*.apk
# 根据用户指定的 ABI 只扫描对应的目录，避免选择到之前遗留的其他架构 APK
$apkRoot = "app/build/outputs/apk"

# 确定要扫描的 ABI 目录
$abiDirMap = @{
    "arm64" = "arm64"
    "x86"   = "x86_64"
    "all"   = $null  # all 时扫描所有目录
}
$targetAbiDir = $abiDirMap[$Abi]

if ($targetAbiDir) {
    # 用户指定了特定 ABI，只扫描对应目录
    $apkSearchPath = Join-Path $apkRoot $targetAbiDir
    if (-not (Test-Path $apkSearchPath)) {
        Write-Host "APK output directory not found: $apkSearchPath" -ForegroundColor Red
        Write-Host "Please run the build first or check the ABI parameter." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Scanning APK directory: $apkSearchPath" -ForegroundColor DarkGray
    $apkFiles = Get-ChildItem -Path $apkSearchPath -Recurse -Filter "*.apk" -File |
        Where-Object {
            $_.FullName -like "*\$Variant\*" -and
            $_.Name -notlike "*-unsigned.apk" -and
            $_.Name -notlike "*.dm"
        }
} else {
    # 用户指定 all，扫描所有目录
    $apkFiles = Get-ChildItem -Path $apkRoot -Recurse -Filter "*.apk" -File |
        Where-Object {
            $_.FullName -like "*\$Variant\*" -and
            $_.Name -notlike "*-unsigned.apk" -and
            $_.Name -notlike "*.dm"
        }
}

if ($apkFiles.Count -eq 0) {
    Write-Host "No APK files found under: $apkRoot (variant=$Variant, abi=$Abi)" -ForegroundColor Red
    Write-Host "Searched in:" -ForegroundColor Yellow
    if ($targetAbiDir) {
        Write-Host "  - $(Join-Path $apkRoot $targetAbiDir)" -ForegroundColor DarkGray
    } else {
        Get-ChildItem -Path $apkRoot -Recurse -Directory | Where-Object { $_.Name -eq $Variant } | ForEach-Object {
            Write-Host "  - $($_.FullName)" -ForegroundColor DarkGray
        }
    }
    exit 1
}

# 如果有多个 APK（ABI 分包），选择合适的
if ($apkFiles.Count -gt 1) {
    Write-Host "Found multiple APK files (ABI splits):" -ForegroundColor Yellow
    $apkFiles | ForEach-Object { Write-Host "  - $($_.Name) ($([math]::Round($_.Length / 1MB, 2)) MB)" -ForegroundColor DarkGray }

    # 根据用户指定的 ABI 优先选择对应的 APK
    # 如果用户指定了特定 ABI，优先选择该 ABI 的 APK
    $userSelectedAbi = if ($Abi -eq "arm64") { "arm64-v8a" } elseif ($Abi -eq "x86") { "x86_64" } else { $null }
    
    $selectedApk = $null
    
    if ($userSelectedAbi) {
        # 用户指定了特定 ABI，优先选择该 ABI
        $selectedApk = $apkFiles | Where-Object { $_.Name -like "*$userSelectedAbi*" } | Select-Object -First 1
        if ($selectedApk) {
            Write-Host "Selected user-specified ABI: $($selectedApk.Name) (ABI: $userSelectedAbi)" -ForegroundColor Cyan
        }
    }
    
    if (-not $selectedApk) {
        # 如果没有指定 ABI 或找不到对应 APK，使用默认优先级
        # 优先选择 arm64-v8a（真机），其次 x86_64（模拟器）
        $preferredAbis = @("arm64-v8a", "x86_64", "armeabi-v7a", "x86", "universal")
        
        foreach ($preferredAbi in $preferredAbis) {
            $selectedApk = $apkFiles | Where-Object { $_.Name -like "*$preferredAbi*" } | Select-Object -First 1
            if ($selectedApk) {
                Write-Host "Auto-selected: $($selectedApk.Name) (ABI: $preferredAbi)" -ForegroundColor Cyan
                break
            }
        }
    }

    if (-not $selectedApk) {
        $selectedApk = $apkFiles[0]
        Write-Host "Using first APK: $($selectedApk.Name)" -ForegroundColor Cyan
    }

    $apkPath = $selectedApk.FullName
} else {
    $apkPath = $apkFiles[0].FullName
}

$apkFile = Get-Item $apkPath
$apkSizeMB = [math]::Round($apkFile.Length / 1MB, 2)

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Build Successful!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

# ========================================
# APK 重命名（Release 构建时自动执行）
# ========================================

$finalApkPath = $apkPath
if ($Variant -eq "release") {
    $versionProps = Join-Path $repoRoot "version.properties"
    $versionName = "unknown"
    if (Test-Path $versionProps) {
        $content = Get-Content $versionProps
        foreach ($line in $content) {
            if ($line -match "^versionName=(.+)$") {
                $versionName = $Matches[1].Trim()
                break
            }
        }
    }

    # 在各自的架构目录中就地重命名 APK 文件
    Write-Host "Renaming APK files in their respective architecture directories..." -ForegroundColor Cyan
    $renamedFiles = @()

    foreach ($apkFile in $apkFiles) {
        $originalName = [System.IO.Path]::GetFileNameWithoutExtension($apkFile.Name)
        $apkDir = Split-Path $apkFile.FullName -Parent

        # 检测 ABI 架构（从原始文件名或目录路径中提取）
        $abiSuffix = ""
        if ($originalName -match "-(arm64-v8a|x86_64|armeabi-v7a|x86|universal)") {
            $abiSuffix = "-$($Matches[1])"
        } elseif ($apkFile.FullName -match "[\\/](arm64-v8a|x86_64|armeabi-v7a|x86|universal)[\\/]") {
            $abiSuffix = "-$($Matches[1])"
        }

        $newApkName = "TinaIDE-$versionName$abiSuffix.apk"
        $newApkPath = Join-Path $apkDir $newApkName

        # 如果新文件名已存在且不是当前文件，先删除
        if ((Test-Path $newApkPath) -and ($newApkPath -ne $apkFile.FullName)) {
            Remove-Item $newApkPath -Force
        }

        # 在原目录中重命名文件
        Rename-Item -Path $apkFile.FullName -NewName $newApkName -Force
        $renamedFiles += $newApkPath

        $renamedSizeMB = [math]::Round((Get-Item $newApkPath).Length / 1MB, 2)
        $relativeDir = $apkDir -replace [regex]::Escape($repoRoot), ""
        Write-Host "  ✓ $newApkName ($renamedSizeMB MB)" -ForegroundColor Green
        Write-Host "     Location: $relativeDir" -ForegroundColor DarkGray

        # 如果这是选中的APK，更新finalApkPath
        if ($apkFile.FullName -eq $apkPath) {
            $finalApkPath = $newApkPath
        }
    }

    # 清理原始未重命名的 APK 文件（app-*-release.apk）
    foreach ($apkFile in $apkFiles) {
        $apkDir = Split-Path $apkFile.FullName -Parent
        $oldApks = Get-ChildItem -Path $apkDir -Filter "app-*-$Variant.apk" -File -ErrorAction SilentlyContinue
        if ($oldApks) {
            foreach ($oldApk in $oldApks) {
                # 确保不删除已重命名的文件
                if ($oldApk.Name -notlike "TinaIDE-*") {
                    Remove-Item $oldApk.FullName -Force -ErrorAction SilentlyContinue
                    Write-Host "  ✓ Removed original: $($oldApk.Name)" -ForegroundColor DarkGray
                }
            }
        }
    }
    
    Write-Host ""
    Write-Host "Summary:" -ForegroundColor Cyan
    Write-Host "  Total APKs renamed: $($renamedFiles.Count)" -ForegroundColor White
    Write-Host "  APKs are organized by architecture in their respective directories" -ForegroundColor DarkGray
    Write-Host ""

    # 更新显示信息
    $apkFile = Get-Item $finalApkPath
    $apkSizeMB = [math]::Round($apkFile.Length / 1MB, 2)
}

Write-Host "APK: $(Split-Path $finalApkPath -Leaf)" -ForegroundColor White
Write-Host "Path: $finalApkPath" -ForegroundColor White
Write-Host "Size: $apkSizeMB MB" -ForegroundColor White
Write-Host ""

# 如果未指定 -Install，则在此结束
if (-not $Install) {
    if ($OpenFolder) {
        $outputDir = Split-Path $finalApkPath -Parent
        Start-Process explorer.exe -ArgumentList $outputDir
    }
    Write-Host "Build completed. Use -Install parameter to install the APK." -ForegroundColor Cyan
    exit 0
}

# ========================================
# 安装阶段（仅在指定 -Install 时执行）
# ========================================

Write-Host "Preparing to install APK..." -ForegroundColor Cyan

function Resolve-AdbExecutable {
    param([string[]]$CandidateDirs)
    foreach ($dir in $CandidateDirs) {
        if ([string]::IsNullOrWhiteSpace($dir)) { continue }
        $adbPath = Join-Path $dir "adb.exe"
        if (Test-Path $adbPath) {
            return [PSCustomObject]@{
                Dir = $dir
                Path = $adbPath
            }
        }
    }
    return $null
}

$candidateDirs = @(
    "D:\Program Files\Microvirt\MEmu",
    "D:\Programs\Android\Sdk\platform-tools"
)
if ($env:ANDROID_HOME) {
    $candidateDirs += (Join-Path $env:ANDROID_HOME "platform-tools")
}
if ($env:ANDROID_SDK_ROOT) {
    $candidateDirs += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools")
}
$adbInfo = Resolve-AdbExecutable -CandidateDirs $candidateDirs
if (-not $adbInfo) {
    Write-Host "adb executable not found in any of the expected locations:" -ForegroundColor Red
    $candidateDirs | ForEach-Object { if ($_){ Write-Host " - $_" -ForegroundColor Yellow } }
    exit 1
}
$adbDir = $adbInfo.Dir
$adbExe = $adbInfo.Path
$script:usingMemuAdb = $adbDir -like "*Microvirt*MEmu*"
Write-Host "Using adb from $adbDir" -ForegroundColor DarkGreen
if (-not ($env:Path -split ";" | ForEach-Object { $_.Trim() } | Where-Object { $_ -eq $adbDir })) {
    $env:Path = "$adbDir;$env:Path"
}

function Get-AdbDevices {
    $output = & $adbExe devices
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to query adb devices." -ForegroundColor Red
        exit $LASTEXITCODE
    }
    $entries = @()
    foreach ($rawLine in $output) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.StartsWith("List of devices")) { continue }
        if ($line.StartsWith("* daemon")) { continue }
        $parts = $line -split "\s+"
        if ($parts.Length -ge 2) {
            $entries += [PSCustomObject]@{
                Id    = $parts[0]
                State = $parts[-1]
            }
        }
    }
    return $entries
}

function Try-ConnectMemuEmulators {
    if (-not $script:usingMemuAdb) {
        return
    }
    $memuPorts = @(21503, 21513, 21523, 21533, 21543, 21553)
    Write-Host "Attempting to connect to running MEmu instances..." -ForegroundColor Yellow
    foreach ($port in $memuPorts) {
        $target = "127.0.0.1:$port"
        $output = & $adbExe connect $target 2>&1
        if ($output) {
            $output | ForEach-Object {
                Write-Host "adb connect $target -> $_" -ForegroundColor DarkGray
            }
        }
    }
}

function Ensure-AdbDeviceAvailable {
    Write-Host "Checking connected adb devices..." -ForegroundColor Cyan
    $devices = Get-AdbDevices
    $onlineDevices = $devices | Where-Object { $_.State -eq "device" }

    if ((($onlineDevices | Measure-Object).Count -eq 0) -and $script:usingMemuAdb) {
        Try-ConnectMemuEmulators
        $devices = Get-AdbDevices
        $onlineDevices = $devices | Where-Object { $_.State -eq "device" }
    }

    if (-not $onlineDevices -or $onlineDevices.Count -eq 0) {
        if ($devices.Count -gt 0) {
            $states = ($devices | ForEach-Object { "$($_.Id) [$($_.State)]" }) -join ", "
            Write-Host "adb detected devices but none are online: $states" -ForegroundColor Red
        } else {
            Write-Host "No adb devices detected. Please connect a device or start an emulator." -ForegroundColor Red
            if ($script:usingMemuAdb) {
                Write-Host "Tip: ensure the MEmu multi-instance manager has a running device or manually run 'adb connect 127.0.0.1:21503' before retrying." -ForegroundColor Yellow
            }
        }
        exit 1
    }
    $ids = $onlineDevices | ForEach-Object { $_.Id }
    Write-Host "Using adb device(s): $($ids -join ', ')" -ForegroundColor DarkGreen
}

Ensure-AdbDeviceAvailable

function Restart-AdbServer {
    param([string]$AdbExe)

    Write-Host "Restarting adb server..." -ForegroundColor Yellow

    $originalPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $AdbExe kill-server *> $null
        Start-Sleep -Seconds 2
        & $AdbExe start-server *> $null
    } catch {
        Write-Host "Failed to restart adb server: $_" -ForegroundColor Red
    } finally {
        $ErrorActionPreference = $originalPreference
    }

    Start-Sleep -Seconds 2
}

function Install-ApkWithRetry {
    param(
        [string]$AdbExe,
        [string]$ApkPath,
        [int]$MaxRetries = 3,
        [int]$TimeoutSeconds = 120
    )

    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        Write-Host "Installing APK (attempt $attempt/$MaxRetries, timeout: ${TimeoutSeconds}s)..." -ForegroundColor Cyan

        try {
            $script:installJob = Start-Job -ScriptBlock {
                param($adb, $apk)
                & $adb install -r $apk 2>&1
            } -ArgumentList $AdbExe, $ApkPath

            $completed = Wait-Job $script:installJob -Timeout $TimeoutSeconds

            if ($completed) {
                $output = Receive-Job $script:installJob
                Remove-Job $script:installJob -Force
                $script:installJob = $null

                $outputStr = $output -join "`n"
                Write-Host $outputStr -ForegroundColor DarkGray

                if ($outputStr -match "Success") {
                    Write-Host "APK installed successfully!" -ForegroundColor Green
                    return $true
                } else {
                    Write-Host "Install attempt $attempt failed." -ForegroundColor Yellow
                }
            } else {
                Write-Host "Install attempt $attempt timed out after ${TimeoutSeconds}s." -ForegroundColor Yellow
                Stop-Job $script:installJob -ErrorAction SilentlyContinue
                Remove-Job $script:installJob -Force -ErrorAction SilentlyContinue
                $script:installJob = $null

                Restart-AdbServer -AdbExe $AdbExe
            }
        }
        catch {
            Write-Host "Install attempt $attempt encountered error: $_" -ForegroundColor Yellow
            if ($script:installJob) {
                Stop-Job $script:installJob -ErrorAction SilentlyContinue
                Remove-Job $script:installJob -Force -ErrorAction SilentlyContinue
                $script:installJob = $null
            }

            Restart-AdbServer -AdbExe $AdbExe
        }

        if ($attempt -lt $MaxRetries) {
            Write-Host "Retrying in 3 seconds..." -ForegroundColor Yellow
            Start-Sleep -Seconds 3
        }
    }

    return $false
}

$installSuccess = Install-ApkWithRetry -AdbExe $adbExe -ApkPath $finalApkPath -MaxRetries 3 -TimeoutSeconds 120
if (-not $installSuccess) {
    Write-Host "APK installation failed after all retries." -ForegroundColor Red
    exit 1
}

Write-Host "Launching com.wuxianggujun.tinaide via Launcher..." -ForegroundColor Cyan
& $adbExe shell monkey -p com.wuxianggujun.tinaide -c android.intent.category.LAUNCHER 1 | Out-Host
if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to launch application via adb." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Build and install completed." -ForegroundColor Green

# 打开输出目录（可选）
if ($OpenFolder) {
    $outputDir = Split-Path $finalApkPath -Parent
    Start-Process explorer.exe -ArgumentList $outputDir
}
