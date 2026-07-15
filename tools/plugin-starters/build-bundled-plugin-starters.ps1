$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $root
# Historical script name kept for now; starter packages are no longer written into APK assets.
$outputRoot = Join-Path $repoRoot "tools/plugin-starters/dist/tinaide.plugin.starters/templates"
$sharedRoot = Join-Path $PSScriptRoot "shared"
$stagingRoot = Join-Path $PSScriptRoot ".bundle"
$zipBuilder = Join-Path $sharedRoot "build_deterministic_zip.py"

$templates = @(
    @{ Name = "config-basic"; Output = "tina-config-plugin.zip" },
    @{ Name = "script-command"; Output = "tina-script-command-plugin.zip" },
    @{ Name = "script-basic"; Output = "tina-script-plugin.zip" },
    @{ Name = "lsp-basic"; Output = "tina-lsp-plugin.zip" }
)

function Resolve-PythonCommand {
    foreach ($name in @("py", "python3", "python")) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
    }
    throw "Python 3 is required to build deterministic starter archives."
}

$pythonCommand = Resolve-PythonCommand

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

    & $pythonCommand $zipBuilder --source $stagingDir --output $outputZip
    if ($LASTEXITCODE -ne 0) {
        throw "Starter archive build failed: $($template.Name)"
    }
    Write-Host "Built $outputZip"
}

Remove-Item $stagingRoot -Recurse -Force
