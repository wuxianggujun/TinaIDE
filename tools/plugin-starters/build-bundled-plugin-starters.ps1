$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $root
# Historical script name kept for now; starter packages are no longer written into APK assets.
$outputRoot = Join-Path $repoRoot "tools/plugin-starters/dist/tinaide.plugin.starters/templates"
$sharedRoot = Join-Path $PSScriptRoot "shared"
$stagingRoot = Join-Path $PSScriptRoot ".bundle"

$templates = @(
    @{ Name = "config-basic"; Output = "tina-config-plugin.zip" },
    @{ Name = "script-command"; Output = "tina-script-command-plugin.zip" },
    @{ Name = "script-basic"; Output = "tina-script-plugin.zip" },
    @{ Name = "lsp-basic"; Output = "tina-lsp-plugin.zip" }
)

function Get-ArchiveRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$BasePath,
        [Parameter(Mandatory = $true)][string]$FilePath
    )

    try {
        return ([System.IO.Path]::GetRelativePath($BasePath, $FilePath)).Replace("\", "/")
    } catch {
        $separator = [System.IO.Path]::DirectorySeparatorChar
        $baseUri = [Uri]($BasePath.TrimEnd($separator) + $separator)
        return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri([Uri]$FilePath).ToString())
    }
}

function Read-ArchiveEntryBytes {
    param([Parameter(Mandatory = $true)][System.IO.FileInfo]$File)

    $textExtensions = @(
        ".json", ".md", ".txt", ".ps1", ".sh", ".lua", ".xml", ".properties",
        ".gradle", ".kts", ".kt", ".java", ".c", ".cpp", ".h", ".hpp", ".cmake", ".pc"
    )
    if ($File.Extension.ToLowerInvariant() -in $textExtensions) {
        $text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $normalized = $text -replace "`r`n", "`n" -replace "`r", "`n"
        return [System.Text.Encoding]::UTF8.GetBytes($normalized)
    }
    return [System.IO.File]::ReadAllBytes($File.FullName)
}

function New-DeterministicZip {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDir,
        [Parameter(Mandatory = $true)][string]$OutputFile
    )

    Add-Type -AssemblyName System.IO.Compression
    if (Test-Path -LiteralPath $OutputFile) {
        Remove-Item -LiteralPath $OutputFile -Force
    }
    $sourcePath = (Resolve-Path -LiteralPath $SourceDir).Path
    $stream = [System.IO.File]::Open($OutputFile, [System.IO.FileMode]::CreateNew)
    $archive = [System.IO.Compression.ZipArchive]::new(
        $stream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false,
        [System.Text.Encoding]::UTF8
    )
    try {
        Get-ChildItem -LiteralPath $sourcePath -File -Recurse -Force |
            ForEach-Object {
                [pscustomobject]@{
                    File = $_
                    RelativePath = Get-ArchiveRelativePath -BasePath $sourcePath -FilePath $_.FullName
                }
            } |
            Sort-Object -Property RelativePath |
            ForEach-Object {
                $entry = $archive.CreateEntry($_.RelativePath, [System.IO.Compression.CompressionLevel]::NoCompression)
                $entry.LastWriteTime = [DateTimeOffset]::new(2020, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
                $entry.ExternalAttributes = 0
                $entryStream = $entry.Open()
                try {
                    $bytes = Read-ArchiveEntryBytes -File $_.File
                    $entryStream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $entryStream.Dispose()
                }
            }
    } finally {
        $archive.Dispose()
        $stream.Dispose()
    }
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
if (Test-Path $stagingRoot) {
    Remove-Item $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

foreach ($template in $templates) {
    $sourceDir = Join-Path $PSScriptRoot $template.Name
    $outputZip = Join-Path $outputRoot $template.Output
    $validateScript = Join-Path $sourceDir "validate.ps1"
    $stagingDir = Join-Path $stagingRoot $template.Name

    & $validateScript
    if ($LASTEXITCODE -ne 0) {
        throw "Starter validation failed: $($template.Name)"
    }

    if (Test-Path $stagingDir) {
        Remove-Item $stagingDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null

    Get-ChildItem $sourceDir -Force | Where-Object {
        $_.Name -notin @("dist", ".pack", ".bundle")
    } | ForEach-Object {
        Copy-Item $_.FullName -Destination $stagingDir -Recurse -Force
    }

    $starterSupportDir = Join-Path $stagingDir ".tina-starter"
    New-Item -ItemType Directory -Force -Path $starterSupportDir | Out-Null
    Copy-Item (Join-Path $sharedRoot "validate-core.ps1") -Destination $starterSupportDir -Force
    Copy-Item (Join-Path $sharedRoot "validate_core.py") -Destination $starterSupportDir -Force
    Copy-Item (Join-Path $sharedRoot "validation-rules.json") -Destination $starterSupportDir -Force

    New-DeterministicZip -SourceDir $stagingDir -OutputFile $outputZip
    Write-Host "Built $outputZip"
}

Remove-Item $stagingRoot -Recurse -Force
