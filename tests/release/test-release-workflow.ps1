[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$workflowPath = Join-Path $root '.github\workflows\release.yml'
$lines = @(Get-Content -LiteralPath $workflowPath)
$workflowPaths = @(
    $workflowPath
    (Join-Path $root '.github\workflows\ci.yml')
    (Join-Path $root '.github\workflows\unix-packages.yml')
    (Join-Path $root '.github\workflows\windows-installer.yml')
    (Join-Path $root '.github\workflows\update-repository-dependencies.yml')
)

foreach ($path in $workflowPaths) {
    $content = Get-Content -Raw -LiteralPath $path
    if ($content -match 'repository:\s*roflsunriz/nlFilters') {
        throw "統合済みnlFiltersの外部checkoutが残っています: $path"
    }
}

function Get-StepBlock {
    param(
        [Parameter(Mandatory)][string]$Name,
        [string[]]$WorkflowLines = $lines
    )

    $heading = "      - name: $Name"
    $start = [Array]::IndexOf($WorkflowLines, $heading)
    if ($start -lt 0) {
        throw "Release workflow step is missing: $Name"
    }
    $end = $WorkflowLines.Count
    for ($index = $start + 1; $index -lt $WorkflowLines.Count; $index++) {
        if ($WorkflowLines[$index] -match '^      - name: ') {
            $end = $index
            break
        }
    }
    return @($WorkflowLines[$start..($end - 1)])
}

function Assert-ContainsLine {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Block,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Context
    )

    if (-not ($Block.Trim() -ccontains $Expected)) {
        throw "$Context does not contain the required line: $Expected"
    }
}

function Assert-ExactSet {
    param(
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string[]]$Actual,
        [Parameter(Mandatory)][string]$Context
    )

    $difference = @(Compare-Object -ReferenceObject $Expected -DifferenceObject $Actual -CaseSensitive)
    if ($difference.Count -ne 0 -or $Expected.Count -ne $Actual.Count) {
        throw "$Context changed unexpectedly.`nExpected: $($Expected -join ', ')`nActual: $($Actual -join ', ')"
    }
}

foreach ($step in @(
        'Run functional and Extension ABI tests',
        'Test release MSI structure',
        'Test release app image',
        'Test ZIP and app image parity',
        'Test release Windows integration and rollback',
        'Create MSI checksum'
    )) {
    Get-StepBlock -Name $step | Out-Null
}

$compatibilityBuilds = @(
    @{
        Path = $workflowPath
        BuildStep = 'Build NicoCache Java applications'
        BuildLine = 'run: .\build-javac.ps1 -JavaVersion 17'
    }
    @{
        Path = Join-Path $root '.github\workflows\ci.yml'
        BuildStep = 'Build NicoCache Java applications'
        BuildLine = 'run: .\build-javac.ps1 -JavaVersion 17'
    }
    @{
        Path = Join-Path $root '.github\workflows\update-repository-dependencies.yml'
        BuildStep = 'Run Java build and functional tests'
        BuildLine = '.\build-javac.ps1 -JavaVersion 17'
    }
)
foreach ($compatibilityBuild in $compatibilityBuilds) {
    $compatibilityLines = @(Get-Content -LiteralPath $compatibilityBuild.Path)
    $setup = Get-StepBlock `
        -Name 'Set up JDK 17 for compatibility build' `
        -WorkflowLines $compatibilityLines
    Assert-ContainsLine $setup "java-version: '17'" 'Compatibility JDK setup'
    $build = Get-StepBlock `
        -Name $compatibilityBuild.BuildStep `
        -WorkflowLines $compatibilityLines
    Assert-ContainsLine $build $compatibilityBuild.BuildLine `
        "Compatibility Java build in $($compatibilityBuild.Path)"
}

$buildApplication = Get-StepBlock -Name 'Build release app image and MSI'
if ($buildApplication -match 'NlFiltersSource') {
    throw 'Release workflow must use the shared system-files manifest'
}
$packageScript = Get-Content -LiteralPath (
    Join-Path $root 'packaging\windows\build-windows-package.ps1'
)
Assert-ContainsLine $packageScript `
    '$systemFilesManifest = Join-Path $root ''packaging\system-files.txt''' `
    'Shared system-files manifest'

$installerWorkflow = Get-Content -Raw -LiteralPath (
    Join-Path $root '.github\workflows\windows-installer.yml'
)
if ($installerWorkflow -match
        '-AppVersion\s+\$env:[A-Z_]+\s+`\r?\n') {
    throw (
        'Windows Installer workflow leaves a trailing backtick after ' +
        'the final AppVersion argument'
    )
}
if (($installerWorkflow.Split(
        [string[]]@("      - 'nlFilters/**'"),
        [System.StringSplitOptions]::None).Count - 1) -ne 2) {
    throw 'Windows Installer workflow must run for push and PR nlFilters changes'
}

$validateUpdaterVersion = Get-StepBlock -Name 'Validate standalone updater version'
Assert-ContainsLine $validateUpdaterVersion `
    '$version = (Get-Content -Raw -LiteralPath .\updater\VERSION).Trim()' `
    'Standalone updater version validation'
Assert-ContainsLine $validateUpdaterVersion '"updater_version=$version" |' `
    'Standalone updater version validation'

$buildUpdater = Get-StepBlock -Name 'Build standalone updater MSI'
Assert-ContainsLine $buildUpdater '-PackageType Msi `' 'Standalone updater build'
Assert-ContainsLine $buildUpdater '-AppVersion $env:UPDATER_VERSION' 'Standalone updater build'

$hashUpdater = Get-StepBlock -Name 'Create standalone updater MSI checksum'
Assert-ContainsLine $hashUpdater '$assetName = "NicoCache_nl-Updater-$env:UPDATER_VERSION.msi"' `
    'Standalone updater checksum'
if (-not (($hashUpdater -join "`n").Contains('Get-FileHash -LiteralPath $releaseMsi -Algorithm SHA256'))) {
    throw 'Standalone updater checksum does not use SHA-256'
}

$releaseContent = Get-Content -Raw -LiteralPath $workflowPath
foreach ($required in @(
        '  build-unix:',
        'platform: Linux',
        'platform: MacOS',
        'packaging/unix/build-package.ps1',
        'packaging/unix/build-standalone-updater.ps1',
        'name: release-assets-${{ matrix.platform }}',
        'merge-multiple: true'
    )) {
    if (-not $releaseContent.Contains($required)) {
        throw "Unix release workflow contract is missing: $required"
    }
}

$upload = Get-StepBlock -Name 'Upload release assets'
Assert-ContainsLine $upload 'if-no-files-found: error' 'Release asset upload'
$uploadedAssets = @($upload | ForEach-Object {
        $trimmed = $_.Trim()
        if ($trimmed -match '^NicoCache_nl(?:-|[.]).+') {
            $trimmed
        }
    })
$expectedUploadedAssets = @(
    'NicoCache_nl-${{ steps.release-tag.outputs.tag }}.zip'
    'NicoCache_nl-${{ steps.release-tag.outputs.app_version }}.msi'
    'NicoCache_nl-${{ steps.release-tag.outputs.app_version }}.msi.sha256'
    'NicoCache_nl-Updater-${{ steps.updater-version.outputs.updater_version }}.msi'
    'NicoCache_nl-Updater-${{ steps.updater-version.outputs.updater_version }}.msi.sha256'
)
Assert-ExactSet $expectedUploadedAssets $uploadedAssets 'Uploaded release asset set'

$publish = Get-StepBlock -Name 'Create GitHub release'
Assert-ContainsLine $publish 'release-assets/* \' 'Published release asset set'
if (($publish -join "`n") -match 'release-assets/NicoCache_nl-') {
    throw 'Release publication must publish the complete staged cross-platform asset set'
}
foreach ($option in @(
        '--repo "$GITHUB_REPOSITORY" \',
        '--verify-tag \',
        '--generate-notes \'
    )) {
    Assert-ContainsLine $publish $option 'GitHub release publication'
}

Write-Output 'Release workflow contract tests passed'
