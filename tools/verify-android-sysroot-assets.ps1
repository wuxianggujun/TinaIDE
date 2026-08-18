[CmdletBinding()]
param(
    [ValidateSet("arm64", "x86_64")]
    [string]$Abi = "arm64",
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:Failures = New-Object System.Collections.Generic.List[string]

function Resolve-RepoRoot {
    if (-not [string]::IsNullOrWhiteSpace($ProjectRoot)) {
        $path = $ProjectRoot
        if (-not [System.IO.Path]::IsPathRooted($path)) {
            $path = Join-Path (Get-Location).Path $path
        }
        return (Resolve-Path -LiteralPath $path).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
}

function Write-Step {
    param([string]$Message)
    Write-Host "[verify-sysroot] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "  OK   $Message" -ForegroundColor Green
}

function Add-Failure {
    param([string]$Message)
    $script:Failures.Add($Message) | Out-Null
    Write-Host "  FAIL $Message" -ForegroundColor Red
}

function Invoke-TarList {
    param([string]$ArchivePath)
    $output = & tar -tf $ArchivePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Add-Failure "tar -tf failed for $ArchivePath`n$output"
        return @()
    }
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Invoke-TarExtractText {
    param(
        [string]$ArchivePath,
        [string]$Entry
    )
    $output = & tar -xOf $ArchivePath $Entry 2>&1
    if ($LASTEXITCODE -ne 0) {
        Add-Failure "tar -xOf failed for $Entry in $ArchivePath`n$output"
        return $null
    }
    return ($output -join [Environment]::NewLine)
}

function Read-PropertiesText {
    param([string]$Text)
    $props = @{}
    if ([string]::IsNullOrWhiteSpace($Text)) { return $props }
    $Text -split "\r?\n" | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $kv = $line.Split("=", 2)
        if ($kv.Count -eq 2) {
            $props[$kv[0].Trim()] = $kv[1].Trim()
        }
    }
    return $props
}

function Get-ExpectedTriple {
    param([string]$Abi)
    switch ($Abi) {
        "arm64" { return "aarch64-linux-android" }
        "x86_64" { return "x86_64-linux-android" }
    }
}

function Get-ExpectedManifestArch {
    param([string]$Abi)
    switch ($Abi) {
        "arm64" { return "ARM64" }
        "x86_64" { return "X86_64" }
    }
}

function Test-EntryExact {
    param(
        [string[]]$Entries,
        [string]$Entry
    )
    return $Entries -contains $Entry
}

function Test-EntryPrefix {
    param(
        [string[]]$Entries,
        [string]$Prefix
    )
    $normalized = if ($Prefix.EndsWith("/")) { $Prefix } else { "$Prefix/" }
    return [bool]($Entries | Where-Object { $_ -eq $Prefix -or $_.StartsWith($normalized) } | Select-Object -First 1)
}

$repoRoot = Resolve-RepoRoot
$assetDir = Join-Path $repoRoot ("app/src/{0}/assets/android-sysroot" -f $Abi)
$manifestPath = Join-Path $assetDir "profiles.json"
$expectedArch = Get-ExpectedManifestArch -Abi $Abi
$expectedTriple = Get-ExpectedTriple -Abi $Abi

Write-Step "repoRoot=$repoRoot"
Write-Step "abi=$Abi assets=$assetDir"

if (-not (Test-Path -LiteralPath $assetDir)) {
    Add-Failure "asset directory not found: $assetDir"
}
if (-not (Test-Path -LiteralPath $manifestPath)) {
    Add-Failure "profiles.json not found: $manifestPath"
}

if ($script:Failures.Count -eq 0) {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int]$manifest.schemaVersion -ne 1) {
        Add-Failure "profiles.json schemaVersion must be 1"
    } else {
        Write-Ok "schemaVersion=1"
    }
    $profiles = @($manifest.profiles)
    if ($profiles.Count -eq 0) {
        Add-Failure "profiles.json contains no profiles"
    }
    if ([string]::IsNullOrWhiteSpace([string]$manifest.defaultProfileId)) {
        Add-Failure "profiles.json defaultProfileId is blank"
    } elseif (-not ($profiles | Where-Object { [string]$_.id -eq [string]$manifest.defaultProfileId } | Select-Object -First 1)) {
        Add-Failure "defaultProfileId does not point to a listed profile: $($manifest.defaultProfileId)"
    } else {
        Write-Ok "defaultProfileId=$($manifest.defaultProfileId)"
    }

    foreach ($profile in $profiles) {
        $id = [string]$profile.id
        $assetPath = [string]$profile.assetPath
        Write-Step "profile=$id"
        if ([string]::IsNullOrWhiteSpace($id)) {
            Add-Failure "profile id is blank"
            continue
        }
        if ([string]$profile.arch -ne $expectedArch) {
            Add-Failure "profile arch mismatch: id=$id arch=$($profile.arch) expected=$expectedArch"
        } else {
            Write-Ok "arch=$expectedArch"
        }
        if ([string]::IsNullOrWhiteSpace($assetPath) -or -not $assetPath.StartsWith("android-sysroot/")) {
            Add-Failure "profile assetPath must start with android-sysroot/: id=$id assetPath=$assetPath"
            continue
        }
        $assetName = [System.IO.Path]::GetFileName($assetPath)
        if ($assetName -ne $assetPath.Substring("android-sysroot/".Length)) {
            Add-Failure "profile assetPath must not contain nested path segments: id=$id assetPath=$assetPath"
            continue
        }
        $archivePath = Join-Path $assetDir $assetName
        if (-not (Test-Path -LiteralPath $archivePath)) {
            Add-Failure "sysroot archive not found: $archivePath"
            continue
        }
        $archiveInfo = Get-Item -LiteralPath $archivePath
        Write-Ok ("archive exists: {0} ({1:N2} MiB)" -f $assetName, ($archiveInfo.Length / 1MB))

        $expectedSha = [string]$profile.sha256
        if (-not [string]::IsNullOrWhiteSpace($expectedSha)) {
            $actualSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
            if ($actualSha -ne $expectedSha.ToLowerInvariant()) {
                Add-Failure "sha256 mismatch: id=$id expected=$expectedSha actual=$actualSha"
            } else {
                Write-Ok "sha256 matches"
            }
        }

        $entries = Invoke-TarList -ArchivePath $archivePath
        if ($entries.Count -eq 0) { continue }
        $requiredEntries = @(
            "android-sysroot/.version",
            "android-sysroot/usr/include",
            "android-sysroot/usr/include/android/api-level.h",
            "android-sysroot/usr/lib/$expectedTriple/libc++_shared.so",
            "android-sysroot/usr/lib/$expectedTriple/libc++_static.a",
            "android-sysroot/usr/lib/$expectedTriple/libc++abi.a"
        )
        foreach ($entry in $requiredEntries) {
            if ($entry -eq "android-sysroot/usr/include") {
                if (Test-EntryPrefix -Entries $entries -Prefix $entry) {
                    Write-Ok "archive entry exists: $entry"
                } else {
                    Add-Failure "archive entry missing: $entry"
                }
            } elseif (Test-EntryExact -Entries $entries -Entry $entry) {
                Write-Ok "archive entry exists: $entry"
            } else {
                Add-Failure "archive entry missing: $entry"
            }
        }

        $apiDirs = @($entries |
            Where-Object { $_ -match "^android-sysroot/usr/lib/$([regex]::Escape($expectedTriple))/[0-9]+/$" } |
            ForEach-Object { ($_ -split "/")[-2] } |
            Sort-Object {[int]$_} -Unique)
        if ($apiDirs.Count -eq 0) {
            Add-Failure "no API level lib directories found under usr/lib/$expectedTriple"
        } else {
            Write-Ok "API levels in archive: $($apiDirs -join ',')"
        }

        $declaredApiLevels = @($profile.apiLevels | ForEach-Object { [int]$_ } | Sort-Object -Unique)
        foreach ($apiLevel in $declaredApiLevels) {
            $apiLibDir = "android-sysroot/usr/lib/$expectedTriple/$apiLevel"
            $apiLibCpp = "$apiLibDir/libc++.a"
            if (-not (Test-EntryPrefix -Entries $entries -Prefix $apiLibDir)) {
                Add-Failure "declared API level directory missing: id=$id apiLevel=$apiLevel"
            } elseif (Test-EntryExact -Entries $entries -Entry $apiLibCpp) {
                Write-Ok "static libc++ linker script exists: API $apiLevel"
            } else {
                Add-Failure "static libc++ linker script missing: id=$id apiLevel=$apiLevel entry=$apiLibCpp"
            }
        }

        $versionText = Invoke-TarExtractText -ArchivePath $archivePath -Entry "android-sysroot/.version"
        $versionProps = Read-PropertiesText -Text $versionText
        if ([string]$versionProps["TOOLCHAIN_TRIPLE"] -ne $expectedTriple) {
            Add-Failure ".version TOOLCHAIN_TRIPLE mismatch: id=$id actual=$($versionProps["TOOLCHAIN_TRIPLE"]) expected=$expectedTriple"
        } else {
            Write-Ok ".version TOOLCHAIN_TRIPLE=$expectedTriple"
        }

        $profileNdkLlvmVersion = if ($profile.PSObject.Properties.Name -contains "ndkLlvmVersion") {
            [string]$profile.ndkLlvmVersion
        } elseif ($profile.PSObject.Properties.Name -contains "llvmVersion") {
            [string]$profile.llvmVersion
        } else {
            ""
        }
        $archiveNdkLlvmVersion = if (-not [string]::IsNullOrWhiteSpace([string]$versionProps["NDK_LLVM_VERSION"])) {
            [string]$versionProps["NDK_LLVM_VERSION"]
        } else {
            [string]$versionProps["LLVM_VERSION"]
        }
        if (-not [string]::IsNullOrWhiteSpace($profileNdkLlvmVersion) -and
            -not [string]::IsNullOrWhiteSpace($archiveNdkLlvmVersion)) {
            if ($profileNdkLlvmVersion -ne $archiveNdkLlvmVersion) {
                Add-Failure "ndkLlvmVersion mismatch: id=$id profile=$profileNdkLlvmVersion archive=$archiveNdkLlvmVersion"
            } else {
                Write-Ok "ndkLlvmVersion=$profileNdkLlvmVersion"
            }
        }
    }
}

if ($script:Failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Verification failed with $($script:Failures.Count) issue(s):" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}

Write-Host ""
Write-Host "Sysroot assets verification passed." -ForegroundColor Green
exit 0
