[CmdletBinding()]
param(
    [ValidateSet("arm64", "x86_64")]
    [string]$Abi = "arm64",
    [string]$ProjectRoot,
    [string]$SpecFile,
    [switch]$SkipBinaryMarkers
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
    Write-Host "[verify-toolchain] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "  OK   $Message" -ForegroundColor Green
}

function Write-WarnLine {
    param([string]$Message)
    Write-Host "  WARN $Message" -ForegroundColor Yellow
}

function Add-Failure {
    param([string]$Message)
    $script:Failures.Add($Message) | Out-Null
    Write-Host "  FAIL $Message" -ForegroundColor Red
}

function Read-PropertiesFile {
    param([string]$Path)
    $map = @{}
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line) { return }
        if ($line.StartsWith("#") -or $line.StartsWith("!")) { return }
        $match = [regex]::Match($line, '^([^:=\s]+)\s*[:=]\s*(.*)$')
        if (-not $match.Success) { return }
        $key = $match.Groups[1].Value.Trim()
        $value = $match.Groups[2].Value.Trim()
        if ($key) { $map[$key] = $value }
    }
    return $map
}

function Require-Property {
    param(
        [hashtable]$Props,
        [string]$Key,
        [string]$Source
    )
    if (-not $Props.ContainsKey($Key) -or [string]::IsNullOrWhiteSpace([string]$Props[$Key])) {
        Add-Failure "$Source missing required property: $Key"
        return $null
    }
    return [string]$Props[$Key]
}

function Get-ArchiveSha256LineMap {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $match = [regex]::Match($line, '^([A-Fa-f0-9]{64})\s+(\*?\S.*)$')
        if (-not $match.Success) { return }
        $hash = $match.Groups[1].Value.ToLowerInvariant()
        $name = $match.Groups[2].Value.Trim()
        if ($name.StartsWith("*")) { $name = $name.Substring(1) }
        $map[$name] = $hash
    }
    return $map
}

function Invoke-TarList {
    param([string]$ArchivePath)
    $output = & tar -tf $ArchivePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "tar -tf failed for $ArchivePath`n$output"
    }
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-TarEntrySizeMap {
    param([string]$ArchivePath)
    $output = & tar -tvf $ArchivePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "tar -tvf failed for $ArchivePath`n$output"
    }
    # bsdtar (Windows): "-rwxr-xr-x  0 root root  11291512 Jun 06 16:03 path"
    # GNU tar:          "-rwxr-xr-x root/root  11291512 2026-06-06 16:03 path"
    $patterns = @(
        '^-\S*\s+\d+\s+\S+\s+\S+\s+(?<size>\d+)\s+.*?(?<name>\S+)$',
        '^-\S*\s+\S+\s+(?<size>\d+)\s+.*?(?<name>\S+)$'
    )
    $map = @{}
    foreach ($line in $output) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^-') { continue }
        foreach ($pattern in $patterns) {
            $match = [regex]::Match($line, $pattern)
            if ($match.Success) {
                $map[$match.Groups["name"].Value] = [long]$match.Groups["size"].Value
                break
            }
        }
    }
    return $map
}

function Invoke-TarExtractText {
    param(
        [string]$ArchivePath,
        [string]$Entry
    )
    $output = & tar -xOf $ArchivePath $Entry 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "tar -xOf failed for $Entry`n$output"
    }
    return ($output -join [Environment]::NewLine)
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

function Test-BinaryContainsText {
    param(
        [string]$Path,
        [string]$Text
    )
    $encoding = [System.Text.Encoding]::GetEncoding("ISO-8859-1")
    $content = $encoding.GetString([System.IO.File]::ReadAllBytes($Path))
    return $content.Contains($Text)
}

function Get-ExpectedArchName {
    param([string]$Abi)
    switch ($Abi) {
        "arm64" { return "aarch64" }
        "x86_64" { return "x86_64" }
        default { return $Abi }
    }
}

$repoRoot = Resolve-RepoRoot
$assetDir = Join-Path $repoRoot ("app/src/{0}/assets/tina-toolchain" -f $Abi)
if ([string]::IsNullOrWhiteSpace($SpecFile)) {
    $SpecFile = Join-Path $assetDir "current.properties"
} elseif (-not [System.IO.Path]::IsPathRooted($SpecFile)) {
    $SpecFile = Join-Path $repoRoot $SpecFile
}

Write-Step "repoRoot=$repoRoot"
Write-Step "abi=$Abi spec=$SpecFile"

if (-not (Test-Path -LiteralPath $SpecFile)) {
    Add-Failure "spec file not found: $SpecFile"
} else {
    Write-Ok "spec file exists"
}

if ($script:Failures.Count -eq 0) {
    $props = Read-PropertiesFile $SpecFile
    $version = Require-Property $props "version" "current.properties"
    $arch = Require-Property $props "arch" "current.properties"
    $shaName = Require-Property $props "sha256" "current.properties"
    $archiveName = $null
    $archiveKey = $null
    if ($props.ContainsKey("full") -and -not [string]::IsNullOrWhiteSpace([string]$props["full"])) {
        $archiveName = [string]$props["full"]
        $archiveKey = "full"
    } elseif ($props.ContainsKey("base") -and -not [string]::IsNullOrWhiteSpace([string]$props["base"])) {
        $archiveName = [string]$props["base"]
        $archiveKey = "base"
    } else {
        Add-Failure "current.properties missing one of full/base"
    }

    if ($arch -and $arch -ne (Get-ExpectedArchName $Abi)) {
        Add-Failure "arch mismatch: spec=$arch expected=$(Get-ExpectedArchName $Abi)"
    } elseif ($arch) {
        Write-Ok "arch=$arch"
    }

    if ($archiveName) {
        if (-not $archiveName.EndsWith(".tar.xz")) {
            Add-Failure "$archiveKey archive must use .tar.xz: $archiveName"
        }
        if ($version -and -not $archiveName.Contains("v$version")) {
            Add-Failure "archive name does not contain version v$version`: $archiveName"
        }
    }

    $archivePath = if ($archiveName) { Join-Path $assetDir $archiveName } else { $null }
    $shaPath = if ($shaName) { Join-Path $assetDir $shaName } else { $null }

    if ($archivePath -and (Test-Path -LiteralPath $archivePath)) {
        $archiveInfo = Get-Item -LiteralPath $archivePath
        Write-Ok ("archive exists: {0} ({1:N2} MiB)" -f $archiveName, ($archiveInfo.Length / 1MB))
    } elseif ($archivePath) {
        Add-Failure "archive not found: $archivePath"
    }

    if ($shaPath -and (Test-Path -LiteralPath $shaPath)) {
        Write-Ok "sha256 file exists: $shaName"
    } elseif ($shaPath) {
        Add-Failure "sha256 file not found: $shaPath"
    }

    if ($script:Failures.Count -eq 0) {
        $shaMap = Get-ArchiveSha256LineMap $shaPath
        if (-not $shaMap.ContainsKey($archiveName)) {
            Add-Failure "sha256 file does not contain exact entry for $archiveName"
        } else {
            Write-Ok "sha256 entry name matches archive"
            $expectedHash = [string]$shaMap[$archiveName]
            $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
            if ($actualHash -ne $expectedHash) {
                Add-Failure "sha256 mismatch for $archiveName`: expected=$expectedHash actual=$actualHash"
            } else {
                Write-Ok "sha256 hash matches"
            }
        }
    }

    if ($script:Failures.Count -eq 0) {
        Write-Step "reading archive table"
        $entries = Invoke-TarList $archivePath
        $topLevels = @($entries |
            ForEach-Object { ($_ -split '/', 2)[0] } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique)

        if ($topLevels.Count -ne 1) {
            Add-Failure "archive should contain exactly one top-level directory, actual=$($topLevels -join ',')"
        } else {
            $root = $topLevels[0]
            Write-Ok "archive root=$root"

            $versionEntry = "$root/VERSION"
            if (-not (Test-EntryExact $entries $versionEntry)) {
                Add-Failure "VERSION entry missing: $versionEntry"
            } else {
                $versionText = Invoke-TarExtractText $archivePath $versionEntry
                $toolchainVersion = $null
                $llvmVersion = $null
                $versionMatch = [regex]::Match($versionText, '(?m)^Toolchain Version:\s*(\S+)\s*$')
                if ($versionMatch.Success) { $toolchainVersion = $versionMatch.Groups[1].Value.Trim() }
                $llvmMatch = [regex]::Match($versionText, '(?m)^LLVM Version:\s*([^\r\n]+)\s*$')
                if ($llvmMatch.Success) { $llvmVersion = $llvmMatch.Groups[1].Value.Trim() }

                if (-not $toolchainVersion) {
                    Add-Failure "VERSION missing Toolchain Version"
                } elseif ($toolchainVersion -ne $version) {
                    Add-Failure "VERSION mismatch: package=$toolchainVersion spec=$version"
                } else {
                    Write-Ok "VERSION Toolchain Version=$toolchainVersion"
                }

                if ($llvmVersion) {
                    Write-Ok "VERSION LLVM Version=$llvmVersion"
                } else {
                    Add-Failure "VERSION missing LLVM Version"
                }

                $llvmMajor = $null
                if ($llvmVersion -and ([regex]::Match($llvmVersion, '^(\d+)')).Success) {
                    $llvmMajor = [regex]::Match($llvmVersion, '^(\d+)').Groups[1].Value
                }

                $requiredFiles = @(
                    "$root/bin/clang",
                    "$root/bin/clang++",
                    "$root/bin/lld",
                    "$root/bin/ld.lld",
                    "$root/bin/clangd"
                )
                if ($llvmMajor) { $requiredFiles += "$root/bin/clang-$llvmMajor" }

                foreach ($entry in $requiredFiles) {
                    if (Test-EntryExact $entries $entry) {
                        Write-Ok "archive entry exists: $entry"
                    } else {
                        Add-Failure "archive entry missing: $entry"
                    }
                }

                if ($llvmMajor) {
                    $resourceDir = "$root/lib/clang/$llvmMajor"
                    if (Test-EntryPrefix $entries $resourceDir) {
                        Write-Ok "clang resource dir exists: $resourceDir"
                    } else {
                        Add-Failure "clang resource dir missing: $resourceDir"
                    }
                }

                # Guard against shipping unstripped CMake tools: `cmake --install` emits
                # ctest/cpack next to cmake, and stripping only cmake once left ~360MB of
                # .debug_info in the x86_64 package. Stripped they are ~12MB each.
                Write-Step "checking cmake tool sizes (strip guard)"
                $sizeMap = Get-TarEntrySizeMap $archivePath
                $stripCeilingBytes = 40MB
                foreach ($tool in @("cmake", "ctest", "cpack")) {
                    $toolEntry = "$root/bin/$tool"
                    if (-not (Test-EntryExact $entries $toolEntry)) { continue }
                    if (-not $sizeMap.ContainsKey($toolEntry)) {
                        Add-Failure "could not read size for $toolEntry from tar listing (strip guard cannot run)"
                        continue
                    }
                    $toolSize = $sizeMap[$toolEntry]
                    $toolMb = [math]::Round($toolSize / 1MB, 2)
                    if ($toolSize -gt $stripCeilingBytes) {
                        Add-Failure "$toolEntry is ${toolMb}MB (> 40MB); looks unstripped - check STRIP_TOOLS_BINARIES in scripts/build-android-tools.sh"
                    } else {
                        Write-Ok "$toolEntry size ok: ${toolMb}MB"
                    }
                }

                if (-not $SkipBinaryMarkers -and $llvmMajor -and $script:Failures.Count -eq 0) {
                    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("tina-toolchain-verify-{0}" -f ([System.Guid]::NewGuid().ToString("N")))
                    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
                    try {
                        $clangEntry = "$root/bin/clang-$llvmMajor"
                        $lldEntry = "$root/bin/lld"
                        Write-Step "extracting marker targets to temp"
                        & tar -C $tempDir -xf $archivePath $clangEntry $lldEntry
                        if ($LASTEXITCODE -ne 0) { throw "tar marker extraction failed" }

                        $markerFiles = @(
                            @{ Name = "clang-$llvmMajor"; Path = Join-Path $tempDir $clangEntry },
                            @{ Name = "lld"; Path = Join-Path $tempDir $lldEntry }
                        )
                        $markers = @(
                            "TINA_EXEC__PROC_SELF_EXE",
                            "TINAIDE_LLVM_EXEC_TRACE",
                            "TINAIDE_LLVM_WRAP_EXEC_LINKER64"
                        )

                        foreach ($file in $markerFiles) {
                            foreach ($marker in $markers) {
                                if (Test-BinaryContainsText -Path $file.Path -Text $marker) {
                                    Write-Ok "$($file.Name) contains marker: $marker"
                                } else {
                                    Add-Failure "$($file.Name) missing marker: $marker"
                                }
                            }
                        }
                    } finally {
                        if (Test-Path -LiteralPath $tempDir) {
                            Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
                        }
                    }
                } elseif ($SkipBinaryMarkers) {
                    Write-WarnLine "binary marker scan skipped"
                }
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
Write-Host "Verification passed." -ForegroundColor Green
exit 0
