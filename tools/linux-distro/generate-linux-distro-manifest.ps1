param(
    [string]$RepoRoot,
    [string]$SourceFile,
    [string]$OutputFile,
    [switch]$RefreshUbuntuMetadata,
    [switch]$RefreshRemoteMetadata,
    [switch]$StampNow
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}
if ([string]::IsNullOrWhiteSpace($SourceFile)) {
    $SourceFile = Join-Path $PSScriptRoot "linux-distros.lock.json"
}
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $RepoRoot "core\linux-distro\src\main\assets\linux-distro\manifest.json"
}

function Write-Utf8JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory) -and -not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    $json = $Value | ConvertTo-Json -Depth 64
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, $encoding)
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Linux distro source file not found: $Path"
    }

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $text = [System.IO.File]::ReadAllText($resolvedPath, [System.Text.Encoding]::UTF8)
    return $text | ConvertFrom-Json
}

function Get-JsonPropertyValue {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($Object.PSObject.Properties.Name -notcontains $Name) {
        throw "Missing $Description property: $Name"
    }

    $value = $Object.$Name
    if ($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)) {
        throw "Blank $Description property: $Name"
    }
    return [string]$value
}

function Assert-SafeId {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($Value -notmatch '^[A-Za-z0-9_.-]+$') {
        throw "Unsafe $Description id: $Value"
    }
}

function Assert-HttpsUrl {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $uri = $null
    if (
        -not [System.Uri]::TryCreate($Value, [System.UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne [System.Uri]::UriSchemeHttps -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrWhiteSpace($uri.UserInfo)
    ) {
        throw "$Description must use a valid HTTPS URL: $Value"
    }
}

function Assert-UniqueKey {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Seen,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($Seen.ContainsKey($Key)) {
        throw "Duplicate ${Description}: $Key"
    }
    $Seen[$Key] = $true
}

function Get-WebText {
    param([Parameter(Mandatory = $true)][string]$Url)

    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 60
    if ($response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($response.Content)
    }
    return [string]$response.Content
}

function Get-RemoteContentLength {
    param([Parameter(Mandatory = $true)][string]$Url)

    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -Method Head -TimeoutSec 60
    $headerValue = $response.Headers["Content-Length"]
    if ($headerValue) {
        $firstHeaderValue = @($headerValue)[0]
        if (-not [string]::IsNullOrWhiteSpace([string]$firstHeaderValue)) {
            return [long]$firstHeaderValue
        }
    }
    if ($response.RawContentLength -gt 0) {
        return [long]$response.RawContentLength
    }
    throw "Cannot resolve remote content length: $Url"
}

function Set-JsonPropertyValue {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        $Value
    )

    if ($Object.PSObject.Properties.Name -contains $Name) {
        $Object.$Name = $Value
    } else {
        $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
}

function Join-UrlPath {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$ChildPath
    )

    return "$($BaseUrl.TrimEnd('/'))/$($ChildPath.TrimStart('/'))"
}

function Expand-SourceFileName {
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$SourceArchitecture
    )

    return $Template.Replace("{version}", $Version).Replace("{sourceArchitecture}", $SourceArchitecture)
}

function Find-Sha256InChecksumText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$FileName,
        [Parameter(Mandatory = $true)][string]$ChecksumUrl
    )

    $pattern = '(?m)^([0-9a-fA-F]{64})\s+\*?' + [regex]::Escape($FileName) + '\s*$'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "Cannot find SHA-256 for $FileName in $ChecksumUrl"
    }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Update-UbuntuMetadata {
    param([Parameter(Mandatory = $true)]$Source)

    foreach ($distro in @($Source.distros)) {
        $family = Get-JsonPropertyValue -Object $distro -Name "family" -Description "distro"
        if ($family -ne "UBUNTU") {
            continue
        }

        foreach ($release in @($distro.releases)) {
            $version = Get-JsonPropertyValue -Object $release -Name "version" -Description "release"
            $baseUrl = Get-JsonPropertyValue -Object $release -Name "sourceBaseUrl" -Description "release"
            $template = Get-JsonPropertyValue -Object $release -Name "sourceFileTemplate" -Description "release"
            $checksumUrl = Get-JsonPropertyValue -Object $release -Name "sourceChecksumUrl" -Description "release"
            $checksumText = Get-WebText -Url $checksumUrl

            foreach ($artifact in @($release.artifacts)) {
                $sourceArchitecture = Get-JsonPropertyValue -Object $artifact -Name "sourceArchitecture" -Description "artifact"
                $fileName = Expand-SourceFileName -Template $template -Version $version -SourceArchitecture $sourceArchitecture
                $url = Join-UrlPath -BaseUrl $baseUrl -ChildPath $fileName
                $sha256 = Find-Sha256InChecksumText -Text $checksumText -FileName $fileName -ChecksumUrl $checksumUrl

                Set-JsonPropertyValue -Object $artifact -Name "url" -Value $url
                Set-JsonPropertyValue -Object $artifact -Name "sourceChecksumUrl" -Value $checksumUrl
                Set-JsonPropertyValue -Object $artifact -Name "sizeBytes" -Value (Get-RemoteContentLength -Url $url)
                $artifact.checksum.value = $sha256
            }
        }
    }
}

function Convert-ToLinuxDistroManifest {
    param([Parameter(Mandatory = $true)]$Source)

    if ([int]$Source.schemaVersion -ne 1) {
        throw "Unsupported linux distro source schema: $($Source.schemaVersion)"
    }

    $sourceMirrors = if ($Source.PSObject.Properties.Name -contains "mirrors") {
        @($Source.mirrors)
    } else {
        @()
    }
    $seenMirrors = @{}
    $mirrors = foreach ($mirror in $sourceMirrors) {
        $matchPrefix = Get-JsonPropertyValue -Object $mirror -Name "matchPrefix" -Description "mirror"
        $replaceWith = Get-JsonPropertyValue -Object $mirror -Name "replaceWith" -Description "mirror"
        Assert-HttpsUrl -Value $matchPrefix -Description "Mirror matchPrefix"
        Assert-HttpsUrl -Value $replaceWith -Description "Mirror replaceWith"
        if (-not $matchPrefix.EndsWith('/') -or -not $replaceWith.EndsWith('/')) {
            throw "Mirror prefixes must end with '/': $matchPrefix -> $replaceWith"
        }
        Assert-UniqueKey -Seen $seenMirrors -Key "$matchPrefix -> $replaceWith" -Description "mirror rule"

        [ordered]@{
            matchPrefix = $matchPrefix
            replaceWith = $replaceWith
        }
    }

    $seenDistros = @{}
    $distros = foreach ($distro in @($Source.distros)) {
        $distroId = Get-JsonPropertyValue -Object $distro -Name "id" -Description "distro"
        Assert-SafeId -Value $distroId -Description "distro"
        Assert-UniqueKey -Seen $seenDistros -Key $distroId -Description "distro id"

        $seenReleases = @{}
        $releases = foreach ($release in @($distro.releases)) {
            $releaseId = Get-JsonPropertyValue -Object $release -Name "id" -Description "release"
            Assert-SafeId -Value $releaseId -Description "release"
            Assert-UniqueKey -Seen $seenReleases -Key $releaseId -Description "release id"

            $seenArtifacts = @{}
            $artifacts = foreach ($artifact in @($release.artifacts)) {
                $architecture = Get-JsonPropertyValue -Object $artifact -Name "architecture" -Description "artifact"
                Assert-UniqueKey -Seen $seenArtifacts -Key $architecture -Description "artifact architecture"

                $url = Get-JsonPropertyValue -Object $artifact -Name "url" -Description "artifact"
                if ($url -notmatch '^https?://') {
                    throw "Artifact URL must be http(s): $url"
                }

                $checksumValue = Get-JsonPropertyValue -Object $artifact.checksum -Name "value" -Description "checksum"
                if ($checksumValue -notmatch '^[0-9a-fA-F]{64}$') {
                    throw "Invalid SHA-256 for ${architecture}: $checksumValue"
                }

                $artifactEntry = [ordered]@{
                    architecture = $architecture
                    url = $url
                    format = Get-JsonPropertyValue -Object $artifact -Name "format" -Description "artifact"
                    checksum = [ordered]@{
                        algorithm = Get-JsonPropertyValue -Object $artifact.checksum -Name "algorithm" -Description "checksum"
                        value = $checksumValue.ToLowerInvariant()
                    }
                }

                if ($artifact.PSObject.Properties.Name -contains "sizeBytes" -and $null -ne $artifact.sizeBytes) {
                    $artifactEntry.sizeBytes = [long]$artifact.sizeBytes
                }
                if ($artifact.PSObject.Properties.Name -contains "signatureUrl" -and -not [string]::IsNullOrWhiteSpace([string]$artifact.signatureUrl)) {
                    $artifactEntry.signatureUrl = [string]$artifact.signatureUrl
                }

                $artifactEntry
            }

            [ordered]@{
                id = $releaseId
                version = Get-JsonPropertyValue -Object $release -Name "version" -Description "release"
                displayName = Get-JsonPropertyValue -Object $release -Name "displayName" -Description "release"
                channel = Get-JsonPropertyValue -Object $release -Name "channel" -Description "release"
                artifacts = @($artifacts)
            }
        }

        [ordered]@{
            id = $distroId
            family = Get-JsonPropertyValue -Object $distro -Name "family" -Description "distro"
            displayName = Get-JsonPropertyValue -Object $distro -Name "displayName" -Description "distro"
            packageManager = Get-JsonPropertyValue -Object $distro -Name "packageManager" -Description "distro"
            defaultReleaseId = Get-JsonPropertyValue -Object $distro -Name "defaultReleaseId" -Description "distro"
            homepageUrl = Get-JsonPropertyValue -Object $distro -Name "homepageUrl" -Description "distro"
            releases = @($releases)
        }
    }

    $generatedAt = if ($Source.PSObject.Properties.Name -contains "generatedAt" -and -not [string]::IsNullOrWhiteSpace([string]$Source.generatedAt)) {
        if ($Source.generatedAt -is [datetime]) {
            $Source.generatedAt.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        } else {
            [string]$Source.generatedAt
        }
    } else {
        (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    }

    return [ordered]@{
        schemaVersion = 1
        generatedAt = $generatedAt
        mirrors = @($mirrors)
        distros = @($distros)
    }
}

$source = Read-JsonFile -Path $SourceFile
if ($RefreshUbuntuMetadata -or $RefreshRemoteMetadata) {
    Update-UbuntuMetadata -Source $source
}
if ($RefreshUbuntuMetadata -or $RefreshRemoteMetadata -or $StampNow) {
    Set-JsonPropertyValue -Object $source -Name "generatedAt" -Value ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"))
    Write-Utf8JsonFile -Path $SourceFile -Value $source
}

$manifest = Convert-ToLinuxDistroManifest -Source $source
Write-Utf8JsonFile -Path $OutputFile -Value $manifest
Write-Host "Generated linux distro manifest: $OutputFile"
