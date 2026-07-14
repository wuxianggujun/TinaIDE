param(
    [string]$PluginRoot = "."
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path $PluginRoot).Path
$rulesPath = Join-Path $scriptDir "validation-rules.json"
$manifestPath = Join-Path $root "manifest.json"

$rules = Get-Content $rulesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$supportedApiVersion = [int]$rules.supportedApiVersion

$knownHostCommands = @{}
foreach ($commandId in @($rules.knownHostCommands)) {
    $knownHostCommands[[string]$commandId] = $true
}

$permissionAliases = @{}
foreach ($property in $rules.permissionAliases.PSObject.Properties) {
    $permissionAliases[$property.Name] = [string]$property.Value
}

$supportedEditorWhenExpressions = @{}
foreach ($expression in @($rules.supportedEditorWhenExpressions)) {
    $supportedEditorWhenExpressions[[string]$expression] = $true
}

$supportedFileTreeWhenExpressions = @{}
foreach ($expression in @($rules.supportedFileTreeWhenExpressions)) {
    $supportedFileTreeWhenExpressions[[string]$expression] = $true
}

$supportedProjectBuildSystems = @{}
foreach ($buildSystem in @($rules.supportedProjectBuildSystems)) {
    $supportedProjectBuildSystems[[string]$buildSystem] = $true
}

$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Add-ValidationError {
    param([string]$Message)
    $script:errors.Add($Message) | Out-Null
}

function Add-ValidationWarning {
    param([string]$Message)
    $script:warnings.Add($Message) | Out-Null
}

function Get-PropValue {
    param(
        $Object,
        [string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Get-Text {
    param($Value)

    if ($null -eq $Value) {
        return ""
    }

    return ([string]$Value).Trim()
}

function Get-Items {
    param($Value)

    if ($null -eq $Value) {
        return @()
    }

    return @($Value)
}

function Test-SafeRelativePath {
    param([string]$PathValue)

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $false
    }

    $normalized = $PathValue.Trim().Replace("\", "/")
    if ($normalized.StartsWith("/")) {
        return $false
    }

    if ($normalized.Contains("../")) {
        return $false
    }

    return $true
}

function Get-Duplicates {
    param([string[]]$Values)

    $counts = @{}
    foreach ($value in $Values) {
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }

        if ($counts.ContainsKey($value)) {
            $counts[$value] += 1
        } else {
            $counts[$value] = 1
        }
    }

    return @(
        $counts.GetEnumerator() |
            Where-Object { $_.Value -gt 1 } |
            ForEach-Object { $_.Key } |
            Sort-Object
    )
}

function Get-EnumerableValues {
    param([System.Collections.IEnumerable]$Values)

    return @($Values | ForEach-Object { $_ })
}

function Test-ContainsPlaceholder {
    param([string]$Value)

    $text = (Get-Text $Value).ToLowerInvariant()
    return (($text.Contains("{{") -and $text.Contains("}}")) -or $text.Contains("replace-me"))
}

function Get-CanonicalPermission {
    param([string]$PermissionId)

    $normalized = Get-Text $PermissionId
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return $null
    }

    if ($script:permissionAliases.ContainsKey($normalized)) {
        return $script:permissionAliases[$normalized]
    }

    return $null
}

function Require-SafePath {
    param(
        [string]$PathValue,
        [string]$FieldName,
        [bool]$MustExist
    )

    $text = Get-Text $PathValue
    if (-not (Test-SafeRelativePath $text)) {
        $displayText = if ([string]::IsNullOrWhiteSpace($text)) { "<empty>" } else { $text }
        Add-ValidationError "$FieldName must be a safe relative path: $displayText"
        return
    }

    if ($MustExist -and -not (Test-Path (Join-Path $script:root $text))) {
        Add-ValidationError "$FieldName does not exist: $text"
    }
}

function Inspect-MenuItems {
    param(
        [string]$Location,
        $Items,
        [hashtable]$SupportedWhenExpressions,
        [bool]$SupportsRuntimePluginCommands,
        [string[]]$DeclaredCommandIds,
        [System.Collections.Generic.HashSet[string]]$CustomMenuCommandIds
    )

    $index = 0
    foreach ($item in (Get-Items $Items)) {
        $index += 1

        if ($null -eq $item -or $item -isnot [pscustomobject]) {
            Add-ValidationError "contributions.menus['$Location'][$index] must be an object."
            continue
        }

        $commandId = Get-Text (Get-PropValue $item "command")
        if ([string]::IsNullOrWhiteSpace($commandId)) {
            Add-ValidationError "contributions.menus['$Location'][$index].command is required."
        } elseif ($script:knownHostCommands.ContainsKey($commandId)) {
            # Host command; statically valid.
        } elseif ($SupportsRuntimePluginCommands) {
            $CustomMenuCommandIds.Add($commandId) | Out-Null
            if ($commandId -notin $DeclaredCommandIds) {
                Add-ValidationWarning (
                    "Menu command '$commandId' in $Location is not declared in contributions.commands."
                )
            }
        } else {
            Add-ValidationError (
                "Menu command '$commandId' in $Location is not a supported host command."
            )
        }

        $whenExpression = Get-Text (Get-PropValue $item "when")
        if (-not [string]::IsNullOrWhiteSpace($whenExpression) -and
            -not $SupportedWhenExpressions.ContainsKey($whenExpression)) {
            Add-ValidationWarning "Unsupported when expression '$whenExpression' in $Location."
        }
    }
}

if (-not (Test-Path $manifestPath)) {
    Add-ValidationError "manifest.json is missing at the plugin root."
    foreach ($message in $errors) {
        Write-Host "[ERROR] $message"
    }
    Write-Host "Validation failed with 1 error(s) and 0 warning(s)."
    exit 1
}

try {
    $manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Add-ValidationError "manifest.json is not valid JSON: $($_.Exception.Message)"
    foreach ($message in $errors) {
        Write-Host "[ERROR] $message"
    }
    Write-Host "Validation failed with 1 error(s) and 0 warning(s)."
    exit 1
}

$pluginId = Get-Text (Get-PropValue $manifest "id")
$pluginName = Get-Text (Get-PropValue $manifest "name")
$pluginVersion = Get-Text (Get-PropValue $manifest "version")
$pluginType = (Get-Text (Get-PropValue $manifest "type")).ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($pluginType)) {
    $pluginType = "config"
}

$contributions = Get-PropValue $manifest "contributions"

if ([string]::IsNullOrWhiteSpace($pluginId)) {
    Add-ValidationError "manifest.id is required."
} elseif (Test-ContainsPlaceholder $pluginId) {
    Add-ValidationWarning "manifest.id still contains template placeholders: $pluginId"
} elseif ($pluginId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]*$') {
    Add-ValidationError "manifest.id is invalid: $pluginId"
} elseif ($pluginId.Contains("..") -or $pluginId.Contains("/") -or $pluginId.Contains("\")) {
    Add-ValidationError "manifest.id must not contain path traversal or separators: $pluginId"
}

if ([string]::IsNullOrWhiteSpace($pluginName)) {
    Add-ValidationError "manifest.name is required."
}

if ([string]::IsNullOrWhiteSpace($pluginVersion)) {
    Add-ValidationError "manifest.version is required."
}

$apiVersionValue = Get-PropValue $manifest "apiVersion"
if ($null -eq $apiVersionValue) {
    $apiVersion = $supportedApiVersion
} else {
    $parsedApiVersion = 0
    if ([int]::TryParse((Get-Text $apiVersionValue), [ref]$parsedApiVersion)) {
        $apiVersion = $parsedApiVersion
    } else {
        $apiVersion = $null
        Add-ValidationError "manifest.apiVersion must be an integer."
    }
}

if ($null -ne $apiVersion -and $apiVersion -ne $supportedApiVersion) {
    Add-ValidationError "Unsupported manifest.apiVersion $apiVersion. Expected $supportedApiVersion."
}

$declaredPermissionsRaw = @()
foreach ($permissionId in (Get-Items (Get-PropValue $manifest "permissions"))) {
    $declaredPermissionsRaw += Get-Text $permissionId
}

$optionalPermissionsRaw = @()
foreach ($permissionId in (Get-Items (Get-PropValue $manifest "optionalPermissions"))) {
    $optionalPermissionsRaw += Get-Text $permissionId
}

$unknownPermissions = [System.Collections.Generic.HashSet[string]]::new()
foreach ($permissionId in ($declaredPermissionsRaw + $optionalPermissionsRaw)) {
    if ([string]::IsNullOrWhiteSpace($permissionId)) {
        continue
    }

    if ($null -eq (Get-CanonicalPermission $permissionId)) {
        $unknownPermissions.Add($permissionId) | Out-Null
    }
}

if ($unknownPermissions.Count -gt 0) {
    Add-ValidationError (
        "Unknown permission id(s): " +
        ((Get-EnumerableValues $unknownPermissions | Sort-Object) -join ", ")
    )
}

$declaredPermissions = @()
foreach ($permissionId in $declaredPermissionsRaw) {
    $canonical = Get-CanonicalPermission $permissionId
    if ($null -ne $canonical) {
        $declaredPermissions += $canonical
    }
}

$optionalPermissions = @()
foreach ($permissionId in $optionalPermissionsRaw) {
    $canonical = Get-CanonicalPermission $permissionId
    if ($null -ne $canonical) {
        $optionalPermissions += $canonical
    }
}

$duplicatePermissions = [System.Collections.Generic.HashSet[string]]::new()
foreach ($permissionId in (Get-Duplicates $declaredPermissions)) {
    $duplicatePermissions.Add($permissionId) | Out-Null
}
foreach ($permissionId in (Get-Duplicates $optionalPermissions)) {
    $duplicatePermissions.Add($permissionId) | Out-Null
}
foreach ($permissionId in $declaredPermissions) {
    if ($permissionId -in $optionalPermissions) {
        $duplicatePermissions.Add($permissionId) | Out-Null
    }
}

if ($duplicatePermissions.Count -gt 0) {
    Add-ValidationWarning (
        "Duplicate permission declarations detected: " +
        ((Get-EnumerableValues $duplicatePermissions | Sort-Object) -join ", ")
    )
}

$allCanonicalPermissions = $declaredPermissions + $optionalPermissions
$hasCommandExecute = "command.execute" -in $allCanonicalPermissions
$hasNetworkPermission = ("network.fetch" -in $allCanonicalPermissions) -or
    ("network.unrestricted" -in $allCanonicalPermissions)

$networkHosts = @()
foreach ($host in (Get-Items (Get-PropValue $manifest "networkHosts"))) {
    $networkHosts += Get-Text $host
}

$invalidHosts = [System.Collections.Generic.HashSet[string]]::new()
foreach ($host in $networkHosts) {
    if ([string]::IsNullOrWhiteSpace($host)) {
        continue
    }

    if ($host.Contains("://") -or $host.Contains("/")) {
        $invalidHosts.Add($host) | Out-Null
    }
}

if ($invalidHosts.Count -gt 0) {
    Add-ValidationError (
        "networkHosts must be hostnames without schemes or paths: " +
        ((Get-EnumerableValues $invalidHosts | Sort-Object) -join ", ")
    )
}

$duplicateHosts = Get-Duplicates ($networkHosts | ForEach-Object { $_.ToLowerInvariant() })
if ($duplicateHosts.Count -gt 0) {
    Add-ValidationWarning (
        "Duplicate networkHosts entries detected: " +
        ($duplicateHosts -join ", ")
    )
}

if ($networkHosts.Count -gt 0 -and -not $hasNetworkPermission) {
    Add-ValidationWarning "networkHosts is declared without network.fetch or network.unrestricted."
}

$locales = Get-PropValue $manifest "locales"
if ($null -ne $locales) {
    if ($locales -isnot [pscustomobject]) {
        Add-ValidationError "manifest.locales must be an object."
    } else {
        $localeFiles = Get-PropValue $locales "files"
        if ($localeFiles -isnot [pscustomobject]) {
            Add-ValidationError "manifest.locales.files must be a non-empty object."
        } else {
            foreach ($property in $localeFiles.PSObject.Properties) {
                $localeKey = Get-Text $property.Name
                $localePath = Get-Text $property.Value
                $normalizedLocalePath = $localePath.Replace("\", "/")
                if ([string]::IsNullOrWhiteSpace($localeKey)) {
                    Add-ValidationError "manifest.locales.files contains an empty locale key."
                }
                if (-not (Test-SafeRelativePath $localePath) -or -not $normalizedLocalePath.StartsWith("locales/")) {
                    $displayPath = if ([string]::IsNullOrWhiteSpace($localePath)) { "<empty>" } else { $localePath }
                    Add-ValidationError "manifest.locales.files['$localeKey'] must point to locales/*.json: $displayPath"
                    continue
                }

                $localeFile = Join-Path $root $localePath
                if (-not (Test-Path $localeFile)) {
                    Add-ValidationError "Locale file does not exist: $localePath"
                    continue
                }

                try {
                    $localeData = Get-Content $localeFile -Raw -Encoding UTF8 | ConvertFrom-Json
                    if ($null -eq $localeData -or $localeData -isnot [pscustomobject]) {
                        Add-ValidationError "Locale file must contain a JSON object: $localePath"
                    }
                } catch {
                    Add-ValidationError "Locale file is not valid JSON: $localePath ($($_.Exception.Message))"
                }
            }
        }
    }
}

if ($pluginType -in @("script", "hybrid")) {
    $mainEntry = Get-Text (Get-PropValue $manifest "main")
    if ([string]::IsNullOrWhiteSpace($mainEntry)) {
        $mainEntry = "main.lua"
    }

    Require-SafePath -PathValue $mainEntry -FieldName "manifest.main" -MustExist $true
}

if ($contributions -is [pscustomobject]) {
    foreach ($themePath in (Get-Items (Get-PropValue $contributions "themes"))) {
        Require-SafePath -PathValue (Get-Text $themePath) -FieldName "contributions.themes[]" -MustExist $true
    }

    foreach ($snippetPath in (Get-Items (Get-PropValue $contributions "snippets"))) {
        Require-SafePath -PathValue (Get-Text $snippetPath) -FieldName "contributions.snippets[]" -MustExist $true
    }

    foreach ($keybindingPath in (Get-Items (Get-PropValue $contributions "keybindings"))) {
        Require-SafePath -PathValue (Get-Text $keybindingPath) -FieldName "contributions.keybindings[]" -MustExist $true
    }

    $projectTemplateIndex = 0
    foreach ($template in (Get-Items (Get-PropValue $contributions "projectTemplates"))) {
        $projectTemplateIndex += 1

        if ($null -eq $template -or $template -isnot [pscustomobject]) {
            Add-ValidationError "contributions.projectTemplates[$projectTemplateIndex] must be an object."
            continue
        }

        $templateId = Get-Text (Get-PropValue $template "id")
        $templateName = Get-Text (Get-PropValue $template "name")
        $templatePath = Get-Text (Get-PropValue $template "templatePath")
        $buildSystem = (Get-Text (Get-PropValue $template "buildSystem")).ToLowerInvariant()

        if ([string]::IsNullOrWhiteSpace($templateId)) {
            Add-ValidationError "contributions.projectTemplates[$projectTemplateIndex].id is required."
        }
        if ([string]::IsNullOrWhiteSpace($templateName)) {
            Add-ValidationError "contributions.projectTemplates[$projectTemplateIndex].name is required."
        }

        Require-SafePath `
            -PathValue $templatePath `
            -FieldName "contributions.projectTemplates[$projectTemplateIndex].templatePath" `
            -MustExist $true

        if (-not $supportedProjectBuildSystems.ContainsKey($buildSystem)) {
            $buildSystemDisplay = if ([string]::IsNullOrWhiteSpace($buildSystem)) { "<empty>" } else { $buildSystem }
            Add-ValidationError (
                "contributions.projectTemplates[$projectTemplateIndex].buildSystem is unsupported: " +
                $buildSystemDisplay
            )
        }
    }

    $apkExportIndex = 0
    foreach ($apkExport in (Get-Items (Get-PropValue $contributions "apkExports"))) {
        $apkExportIndex += 1

        if ($null -eq $apkExport -or $apkExport -isnot [pscustomobject]) {
            Add-ValidationError "contributions.apkExports[$apkExportIndex] must be an object."
            continue
        }

        $exportId = Get-Text (Get-PropValue $apkExport "id")
        $exportName = Get-Text (Get-PropValue $apkExport "name")
        $templatePath = Get-Text (Get-PropValue $apkExport "templatePath")

        if ([string]::IsNullOrWhiteSpace($exportId)) {
            Add-ValidationError "contributions.apkExports[$apkExportIndex].id is required."
        }
        if ([string]::IsNullOrWhiteSpace($exportName)) {
            Add-ValidationError "contributions.apkExports[$apkExportIndex].name is required."
        }

        Require-SafePath `
            -PathValue $templatePath `
            -FieldName "contributions.apkExports[$apkExportIndex].templatePath" `
            -MustExist $true
    }
}

$supportsRuntimePluginCommands = $pluginType -in @("script", "hybrid")
$declaredCommandIds = @()
if ($contributions -is [pscustomobject]) {
    $commandIndex = 0
    foreach ($command in (Get-Items (Get-PropValue $contributions "commands"))) {
        $commandIndex += 1

        if ($null -eq $command -or $command -isnot [pscustomobject]) {
            Add-ValidationError "contributions.commands[$commandIndex] must be an object."
            continue
        }

        $commandId = Get-Text (Get-PropValue $command "id")
        $commandTitle = Get-Text (Get-PropValue $command "title")

        if ([string]::IsNullOrWhiteSpace($commandId)) {
            Add-ValidationError "contributions.commands[$commandIndex].id is required."
        } else {
            $declaredCommandIds += $commandId
            if (-not $knownHostCommands.ContainsKey($commandId) -and -not $supportsRuntimePluginCommands) {
                Add-ValidationWarning (
                    "Command '$commandId' is not a supported host command for $pluginType plugins."
                )
            }
        }

        if ([string]::IsNullOrWhiteSpace($commandTitle)) {
            Add-ValidationError "contributions.commands[$commandIndex].title is required."
        }
    }
}

$duplicateCommandIds = Get-Duplicates $declaredCommandIds
if ($duplicateCommandIds.Count -gt 0) {
    Add-ValidationError "Duplicate command id(s) detected: $($duplicateCommandIds -join ', ')"
}

$declaredCustomCommandIds = [System.Collections.Generic.HashSet[string]]::new()
foreach ($commandId in $declaredCommandIds) {
    if (-not $knownHostCommands.ContainsKey($commandId)) {
        $declaredCustomCommandIds.Add($commandId) | Out-Null
    }
}

$customMenuCommandIds = [System.Collections.Generic.HashSet[string]]::new()
if ($contributions -is [pscustomobject]) {
    $menus = Get-PropValue $contributions "menus"
    if ($menus -is [pscustomobject]) {
        Inspect-MenuItems `
            -Location "editor/context" `
            -Items (Get-PropValue $menus "editor/context") `
            -SupportedWhenExpressions $supportedEditorWhenExpressions `
            -SupportsRuntimePluginCommands $supportsRuntimePluginCommands `
            -DeclaredCommandIds $declaredCommandIds `
            -CustomMenuCommandIds $customMenuCommandIds

        Inspect-MenuItems `
            -Location "editor/toolbar" `
            -Items (Get-PropValue $menus "editor/toolbar") `
            -SupportedWhenExpressions $supportedEditorWhenExpressions `
            -SupportsRuntimePluginCommands $supportsRuntimePluginCommands `
            -DeclaredCommandIds $declaredCommandIds `
            -CustomMenuCommandIds $customMenuCommandIds

        Inspect-MenuItems `
            -Location "filetree/context" `
            -Items (Get-PropValue $menus "filetree/context") `
            -SupportedWhenExpressions $supportedFileTreeWhenExpressions `
            -SupportsRuntimePluginCommands $supportsRuntimePluginCommands `
            -DeclaredCommandIds $declaredCommandIds `
            -CustomMenuCommandIds $customMenuCommandIds

    }

    $panels = @(Get-Items (Get-PropValue $contributions "panels"))
    if ($panels.Count -gt 0 -and $pluginType -notin @("script", "hybrid")) {
        Add-ValidationError "contributions.panels is supported only for script or hybrid plugins."
    }
    if ($panels.Count -gt 16) {
        Add-ValidationError "A plugin may declare at most 16 panels."
    }
    $panelIds = @()
    $panelIndex = 0
    foreach ($panel in $panels) {
        $panelIndex += 1
        if ($null -eq $panel -or $panel -isnot [pscustomobject]) {
            Add-ValidationError "contributions.panels[$panelIndex] must be an object."
            continue
        }
        $panelId = Get-Text (Get-PropValue $panel "id")
        $panelTitle = Get-Text (Get-PropValue $panel "title")
        if ([string]::IsNullOrWhiteSpace($panelId) -or $panelId.Length -gt 128 -or
            $panelId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]*$') {
            Add-ValidationError "contributions.panels[$panelIndex].id is invalid: $panelId"
        } else {
            $panelIds += $panelId
        }
        if ([string]::IsNullOrWhiteSpace($panelTitle) -or $panelTitle.Length -gt 128) {
            Add-ValidationError (
                "contributions.panels[$panelIndex].title is required and must not exceed 128 characters."
            )
        }
    }
    $duplicatePanelIds = Get-Duplicates $panelIds
    if ($duplicatePanelIds.Count -gt 0) {
        Add-ValidationError "Duplicate panel id(s) detected: $($duplicatePanelIds -join ', ')"
    }

    if ($supportsRuntimePluginCommands -and -not $hasCommandExecute) {
        $customCommandIds = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($commandId in (Get-EnumerableValues $declaredCustomCommandIds)) {
            $customCommandIds.Add($commandId) | Out-Null
        }
        foreach ($commandId in (Get-EnumerableValues $customMenuCommandIds)) {
            $customCommandIds.Add($commandId) | Out-Null
        }

        if ($customCommandIds.Count -gt 0) {
            Add-ValidationWarning (
                "Custom commands are declared without command.execute permission: " +
                ((Get-EnumerableValues $customCommandIds | Sort-Object) -join ", ")
            )
        }
    }

    $fileIconIndex = 0
    foreach ($icon in (Get-Items (Get-PropValue $contributions "fileIcons"))) {
        $fileIconIndex += 1

        if ($null -eq $icon -or $icon -isnot [pscustomobject]) {
            Add-ValidationError "contributions.fileIcons[$fileIconIndex] must be an object."
            continue
        }

        $iconSpec = Get-Text (Get-PropValue $icon "icon")
        if ([string]::IsNullOrWhiteSpace($iconSpec)) {
            Add-ValidationWarning "contributions.fileIcons[$fileIconIndex].icon is empty."
            continue
        }

        $extensions = @()
        foreach ($value in (Get-Items (Get-PropValue $icon "extensions"))) {
            $normalized = (Get-Text $value).TrimStart(".").ToLowerInvariant()
            if (-not [string]::IsNullOrWhiteSpace($normalized)) {
                $extensions += $normalized
            }
        }

        $fileNames = @()
        foreach ($value in (Get-Items (Get-PropValue $icon "fileNames"))) {
            $normalized = (Get-Text $value).ToLowerInvariant()
            if (-not [string]::IsNullOrWhiteSpace($normalized)) {
                $fileNames += $normalized
            }
        }

        if ($extensions.Count -eq 0 -and $fileNames.Count -eq 0) {
            Add-ValidationWarning (
                "contributions.fileIcons[$fileIconIndex] should declare extensions or fileNames."
            )
        }

        if ($iconSpec.ToLowerInvariant().StartsWith("builtin:")) {
            continue
        }

        if (-not (Test-SafeRelativePath $iconSpec)) {
            Add-ValidationError (
                "contributions.fileIcons[$fileIconIndex].icon must be a safe relative path: $iconSpec"
            )
            continue
        }

        if (-not (Test-Path (Join-Path $root $iconSpec))) {
            Add-ValidationError "contributions.fileIcons[$fileIconIndex].icon does not exist: $iconSpec"
        }
    }
}

$activationEvents = @()
foreach ($activationEvent in (Get-Items (Get-PropValue $manifest "activationEvents"))) {
    $activationEvents += Get-Text $activationEvent
}
if ($activationEvents.Count -gt 0 -and $pluginType -ne "lsp") {
    Add-ValidationError "manifest.activationEvents is supported only for LSP plugins."
}
$duplicateActivationEvents = Get-Duplicates $activationEvents
if ($duplicateActivationEvents.Count -gt 0) {
    Add-ValidationError "Duplicate activation event(s) detected: $($duplicateActivationEvents -join ', ')"
}
$contributedLanguages = [System.Collections.Generic.HashSet[string]]::new()
if ($contributions -is [pscustomobject]) {
    foreach ($server in (Get-Items (Get-PropValue $contributions "languageServers"))) {
        if ($server -isnot [pscustomobject]) {
            continue
        }
        foreach ($language in (Get-Items (Get-PropValue $server "languages"))) {
            $languageId = Get-Text $language
            if (-not [string]::IsNullOrWhiteSpace($languageId)) {
                $contributedLanguages.Add($languageId) | Out-Null
            }
        }
    }
}
foreach ($activationEvent in $activationEvents) {
    if ($activationEvent -notmatch '^onLanguage:([a-zA-Z0-9][a-zA-Z0-9._+-]*)$') {
        Add-ValidationError (
            "Unsupported activation event '$activationEvent'. " +
            "apiVersion 1 supports only onLanguage:<languageId>."
        )
    } elseif (-not $contributedLanguages.Contains($Matches[1])) {
        Add-ValidationError (
            "Activation event language '$($Matches[1])' is not declared in contributions.languageServers."
        )
    }
}

foreach ($message in $errors) {
    Write-Host "[ERROR] $message"
}

foreach ($message in $warnings) {
    Write-Host "[WARN] $message"
}

if ($errors.Count -gt 0) {
    Write-Host "Validation failed with $($errors.Count) error(s) and $($warnings.Count) warning(s)."
    exit 1
}

if ($warnings.Count -gt 0) {
    Write-Host "Validation passed with $($warnings.Count) warning(s)."
} else {
    Write-Host "Validation passed."
}
