[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$workflowPath = Join-Path $root '.github\workflows\release.yml'
$lines = @(Get-Content -LiteralPath $workflowPath)
$workflowPaths = @(
    $workflowPath
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
    param([Parameter(Mandatory)][string]$Name)

    $heading = "      - name: $Name"
    $start = [Array]::IndexOf($lines, $heading)
    if ($start -lt 0) {
        throw "Release workflow step is missing: $Name"
    }
    $end = $lines.Count
    for ($index = $start + 1; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match '^      - name: ') {
            $end = $index
            break
        }
    }
    return @($lines[$start..($end - 1)])
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
        'Create release checksum',
        'Test release MSI structure',
        'Test release app image',
        'Test ZIP and app image parity',
        'Test release Windows integration and rollback',
        'Create MSI checksum'
    )) {
    Get-StepBlock -Name $step | Out-Null
}

$buildApplication = Get-StepBlock -Name 'Build release app image and MSI'
Assert-ContainsLine $buildApplication '-NlFiltersSource .\nlFilters `' `
    'Bundled nlFilters source'

$installerWorkflow = Get-Content -Raw -LiteralPath (
    Join-Path $root '.github\workflows\windows-installer.yml'
)
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
    'NicoCache_nl.jar'
    'NicoCache_nl.jar.sha256'
    'NicoCache_nl-${{ steps.release-tag.outputs.app_version }}.msi'
    'NicoCache_nl-${{ steps.release-tag.outputs.app_version }}.msi.sha256'
    'NicoCache_nl-Updater-${{ steps.updater-version.outputs.updater_version }}.msi'
    'NicoCache_nl-Updater-${{ steps.updater-version.outputs.updater_version }}.msi.sha256'
)
Assert-ExactSet $expectedUploadedAssets $uploadedAssets 'Uploaded release asset set'

$publish = Get-StepBlock -Name 'Create GitHub release'
$publishedAssets = @($publish | ForEach-Object {
        if ($_.Trim() -match '^"release-assets/(?<asset>[^"]+)"') {
            $Matches.asset
        }
    })
$expectedPublishedAssets = @(
    'NicoCache_nl-$RELEASE_TAG.zip'
    'NicoCache_nl.jar'
    'NicoCache_nl.jar.sha256'
    'NicoCache_nl-$APP_VERSION.msi'
    'NicoCache_nl-$APP_VERSION.msi.sha256'
    'NicoCache_nl-Updater-$UPDATER_VERSION.msi'
    'NicoCache_nl-Updater-$UPDATER_VERSION.msi.sha256'
)
Assert-ExactSet $expectedPublishedAssets $publishedAssets 'Published release asset set'
foreach ($option in @(
        '--repo "$GITHUB_REPOSITORY" \',
        '--verify-tag \',
        '--generate-notes \'
    )) {
    Assert-ContainsLine $publish $option 'GitHub release publication'
}

Write-Output 'Release workflow contract tests passed'
