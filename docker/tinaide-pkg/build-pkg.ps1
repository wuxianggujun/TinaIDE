<#
.SYNOPSIS
    TinaIDE Package Builder - Windows PowerShell orchestration script
.DESCRIPTION
    Builds Android NDK native libraries (static or shared).
    Supports selecting a library, architecture, and link type.
.PARAMETER Library
    Library to build. Use -List to show all supported package IDs, or all for the complete matrix.
.PARAMETER Arch
    Target architecture: arm64-v8a, armeabi-v7a, x86_64, x86, all
.PARAMETER LinkType
    Link type: static, shared, all
.PARAMETER Mode
    Build mode: incremental (default), rebuild, clean
.PARAMETER List
    Lists supported libraries and existing artifacts.
.EXAMPLE
    .\build-pkg.ps1 -Library zlib -Arch arm64-v8a -LinkType static
.EXAMPLE
    .\build-pkg.ps1 -Library all -Arch all -LinkType static
.EXAMPLE
    .\build-pkg.ps1 -List
#>

param(
    [Parameter(Position = 0)]
    [ValidateSet("zlib", "openssl", "curl", "libssh2", "libgit2", "pcre2", "sdl2", "sdl3", "sdl2-image", "sdl2-ttf", "sdl2-mixer", "sdl2-net", "sdl3-image", "sdl3-ttf", "sdl3-mixer", "sdl3-net", "raylib", "box2d", "all")]
    [string]$Library = "all",

    [Parameter(Position = 1)]
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "all")]
    [string]$Arch = "arm64-v8a",

    [Parameter(Position = 2)]
    [ValidateSet("static", "shared", "all")]
    [string]$LinkType = "static",

    [ValidateSet("incremental", "rebuild", "clean")]
    [string]$Mode = "incremental",

    [switch]$List,
    [switch]$Help
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Keep this script ASCII-only so Windows PowerShell 5.1 can load it without a UTF-8 BOM.
# ===== Configuration =====
$IMAGE_NAME = "tinaide-pkg-builder"
$CONTAINER_NAME = "tinaide-pkg-build"
$SOURCE_VOLUME = "tinaide-pkg-source"
$OUTPUT_DIR = Join-Path $ScriptDir "output"

# Supported architectures
$ALL_ARCHS = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

# Libraries in dependency order
$ALL_LIBS = @(
    "zlib", "openssl", "pcre2", "libssh2", "curl", "libgit2",
    "sdl2", "sdl3",
    "sdl2-image", "sdl2-ttf", "sdl2-mixer", "sdl2-net",
    "sdl3-image", "sdl3-ttf", "sdl3-mixer", "sdl3-net",
    "raylib", "box2d"
)
$SHARED_ONLY_LIBS = @(
    "sdl2-image", "sdl2-ttf", "sdl2-mixer", "sdl2-net",
    "sdl3-image", "sdl3-ttf", "sdl3-mixer", "sdl3-net",
    "raylib"
)
$STATIC_ONLY_LIBS = @("box2d")

# ===== Helpers =====
function Write-Info { param($Message) Write-Host "[INFO] $Message" -ForegroundColor Cyan }
function Write-Success { param($Message) Write-Host "[SUCCESS] $Message" -ForegroundColor Green }
function Write-Warn { param($Message) Write-Host "[WARN] $Message" -ForegroundColor Yellow }
function Write-Err { param($Message) Write-Host "[ERROR] $Message" -ForegroundColor Red }

function Test-LibraryLinkType {
    param(
        [Parameter(Mandatory = $true)][string]$Lib,
        [Parameter(Mandatory = $true)][string]$Link
    )

    if ($SHARED_ONLY_LIBS -contains $Lib) {
        return $Link -eq "shared"
    }
    if ($STATIC_ONLY_LIBS -contains $Lib) {
        return $Link -eq "static"
    }
    return $true
}

function Show-Help {
    Get-Help $MyInvocation.PSCommandPath -Detailed
    Write-Host ""
    Write-Host "Available libraries:" -ForegroundColor Yellow
    $ALL_LIBS | ForEach-Object { Write-Host "  - $_" }
    Write-Host ""
    Write-Host "Supported architectures:" -ForegroundColor Yellow
    $ALL_ARCHS | ForEach-Object { Write-Host "  - $_" }
    Write-Host ""
    Write-Host "Dependencies:" -ForegroundColor Yellow
    Write-Host "  libssh2  -> openssl, zlib"
    Write-Host "  libgit2  -> openssl, libssh2, zlib"
    Write-Host "  curl     -> openssl (optional)"
    Write-Host "  sdl2     -> none (standalone, relocated Android Java/JNI bridge)"
    Write-Host "  sdl3     -> none (standalone)"
    Write-Host "  sdl2-*   -> sdl2 (shared only)"
    Write-Host "  sdl3-*   -> sdl3 (shared only)"
    Write-Host "  raylib   -> none (shared only)"
    Write-Host "  box2d    -> none (static only)"
}

function Show-List {
    Write-Info "Available libraries:"
    $ALL_LIBS | ForEach-Object { Write-Host "  - $_" }

    Write-Host ""
    Write-Info "Existing artifacts:"

    if (Test-Path $OUTPUT_DIR) {
        Get-ChildItem -Path $OUTPUT_DIR -Recurse -Filter "*.tar.xz" | ForEach-Object {
            $size = "{0:N2} KB" -f ($_.Length / 1KB)
            $relativePath = $_.FullName.Replace($OUTPUT_DIR, "").TrimStart("\", "/")
            Write-Host "  $relativePath ($size)"
        }
    }
    else {
        Write-Host "  (none)"
    }
}

# ===== Docker operations =====
function Ensure-DockerImage {
    Write-Info "Checking Docker image..."

    $imageExists = docker images -q $IMAGE_NAME 2>$null

    if ($Mode -eq "rebuild" -or -not $imageExists) {
        Write-Info "Building Docker image $IMAGE_NAME ..."
        docker build -t $IMAGE_NAME $ScriptDir

        if ($LASTEXITCODE -ne 0) {
            Write-Err "Docker image build failed"
            exit 1
        }
        Write-Success "Docker image build completed"
    }
    else {
        Write-Info "Using existing image: $IMAGE_NAME"
    }
}

function Ensure-SourceVolume {
    $volumeExists = docker volume ls -q --filter "name=$SOURCE_VOLUME"

    if ($Mode -eq "clean" -and $volumeExists) {
        Write-Info "Removing source volume..."
        docker volume rm $SOURCE_VOLUME 2>$null
    }

    if (-not (docker volume ls -q --filter "name=$SOURCE_VOLUME")) {
        Write-Info "Creating source volume: $SOURCE_VOLUME"
        docker volume create $SOURCE_VOLUME
    }
}

function Run-Build {
    param(
        [string]$Lib,
        [string]$Architecture,
        [string]$Link
    )

    Write-Info "=========================================="
    Write-Info "Building: $Lib ($Architecture, $Link)"
    Write-Info "=========================================="

    # Ensure the output directory exists.
    $libOutputDir = Join-Path (Join-Path $OUTPUT_DIR $Lib) $Architecture
    if (-not (Test-Path $libOutputDir)) {
        New-Item -ItemType Directory -Path $libOutputDir -Force | Out-Null
    }

    # Run the build container.
    $cmd = @(
        "docker", "run", "--rm",
        "-v", "${SOURCE_VOLUME}:/build/src",
        "-v", "${OUTPUT_DIR}:/output",
        $IMAGE_NAME,
        "/build/build.sh", $Lib, $Architecture, $Link
    )

    Write-Info "Executing: $($cmd -join ' ')"

    $dockerExe = $cmd[0]
    $dockerArgs = $cmd[1..($cmd.Length - 1)]
    & $dockerExe @dockerArgs

    if ($LASTEXITCODE -ne 0) {
        Write-Err "Build failed: $Lib ($Architecture, $Link)"
        return $false
    }

    Write-Success "Build completed: $Lib ($Architecture, $Link)"
    return $true
}

# ===== Main =====
function Main {
    if ($Help) {
        Show-Help
        return
    }

    if ($List) {
        Show-List
        return
    }

    Write-Info "TinaIDE Package Builder"
    Write-Info "======================="
    Write-Info "Library: $Library"
    Write-Info "Architecture: $Arch"
    Write-Info "Link type: $LinkType"
    Write-Info "Mode: $Mode"

    # Clean mode
    if ($Mode -eq "clean") {
        Write-Info "Cleaning..."

        # Remove artifacts.
        if (Test-Path $OUTPUT_DIR) {
            Remove-Item -Recurse -Force $OUTPUT_DIR
            Write-Info "Removed output directory"
        }

        # Remove Docker resources.
        docker rm -f $CONTAINER_NAME 2>$null
        docker volume rm $SOURCE_VOLUME 2>$null
        docker rmi $IMAGE_NAME 2>$null

        Write-Success "Cleanup completed"
        return
    }

    # Prepare Docker.
    Ensure-DockerImage
    Ensure-SourceVolume

    # Ensure the output directory exists.
    if (-not (Test-Path $OUTPUT_DIR)) {
        New-Item -ItemType Directory -Path $OUTPUT_DIR -Force | Out-Null
    }

    # Expand aggregate parameters.
    $targetLibs = if ($Library -eq "all") { $ALL_LIBS } else { @($Library) }
    $targetArchs = if ($Arch -eq "all") { $ALL_ARCHS } else { @($Arch) }
    $targetLinks = if ($LinkType -eq "all") { @("static", "shared") } else { @($LinkType) }

    $buildTargets = @(
        foreach ($lib in $targetLibs) {
            foreach ($arch in $targetArchs) {
                foreach ($link in $targetLinks) {
                    if (Test-LibraryLinkType -Lib $lib -Link $link) {
                        [PSCustomObject]@{
                            Library = $lib
                            Architecture = $arch
                            Link = $link
                        }
                    }
                    else {
                        Write-Warn "Skipping unsupported combination: $lib ($arch, $link)"
                    }
                }
            }
        }
    )
    if ($buildTargets.Count -eq 0) {
        Write-Err "No supported build combinations were selected"
        exit 1
    }

    # Counters
    $total = $buildTargets.Count
    $current = 0
    $success = 0
    $failed = 0

    $startTime = Get-Date

    # Build loop
    foreach ($buildTarget in $buildTargets) {
        $current++
        Write-Info "Progress: $current / $total"

        if (Run-Build `
            -Lib $buildTarget.Library `
            -Architecture $buildTarget.Architecture `
            -Link $buildTarget.Link
        ) {
            $success++
        }
        else {
            $failed++
        }
    }

    $elapsed = (Get-Date) - $startTime

    Write-Host ""
    Write-Info "=========================================="
    Write-Info "Build completed"
    Write-Info "=========================================="
    Write-Info "Succeeded: $success"
    Write-Info "Failed: $failed"
    Write-Info "Elapsed: $($elapsed.ToString('hh\:mm\:ss'))"
    Write-Info "Output directory: $OUTPUT_DIR"

    # List generated artifacts.
    Write-Host ""
    Show-List

    if ($failed -gt 0) {
        exit 1
    }
}

Main
