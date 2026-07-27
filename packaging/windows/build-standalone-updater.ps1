[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$AppVersion = '0.1.0',

    [ValidateSet('AppImage', 'Msi', 'All')]
    [string]$PackageType = 'AppImage'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$workRoot = Join-Path $root '.test-work\standalone-updater'
$classesRoot = Join-Path $workRoot 'classes'
$inputRoot = Join-Path $workRoot 'input'
$outputRoot = Join-Path $workRoot 'output'
$buildLog = Join-Path $workRoot 'jpackage.log'

if (Test-Path -LiteralPath $workRoot) { Remove-Item -LiteralPath $workRoot -Recurse -Force }
New-Item -ItemType Directory -Path $classesRoot, $inputRoot, $outputRoot | Out-Null

$javac = (Get-Command javac -ErrorAction Stop).Source
$jar = (Get-Command jar -ErrorAction Stop).Source
$jpackage = (Get-Command jpackage -ErrorAction Stop).Source

$source = Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java'
& $javac --release 11 -encoding UTF-8 -d $classesRoot $source
if ($LASTEXITCODE -ne 0) { throw 'NicoCache_nl Updaterのコンパイルに失敗しました' }

$manifest = Join-Path $workRoot 'manifest.mf'
@('Manifest-Version: 1.0', 'Main-Class: dareka.updater.NicoCacheUpdater', '') |
    Set-Content -LiteralPath $manifest -Encoding ascii
$jarPath = Join-Path $inputRoot 'NicoCacheUpdater.jar'
& $jar cfm $jarPath $manifest -C $classesRoot .
if ($LASTEXITCODE -ne 0) { throw 'NicoCache_nl UpdaterのJAR作成に失敗しました' }

# Bundle the actual engine. packaging/windows/runtime contains installer wrappers and is not usable here.
$engineInput = Join-Path $inputRoot 'extensions'
New-Item -ItemType Directory -Path $engineInput | Out-Null
foreach ($name in @('runtime-dependencies.psd1', 'apply-pending-runtime-update.ps1')) {
    $sourceFile = Join-Path $root "extensions\$name"
    if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Updater同梱エンジンが見つかりません: $sourceFile"
    }
    Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $engineInput $name)
}

$engineSource = Join-Path $root 'extensions\update-runtime-dependencies.ps1'
$engineDestination = Join-Path $engineInput 'update-runtime-dependencies.ps1'
if (-not (Test-Path -LiteralPath $engineSource -PathType Leaf)) {
    throw "Updater同梱エンジンが見つかりません: $engineSource"
}
# Windows PowerShell 5.1 has no $IsWindows automatic variable. The updater deliberately
# invokes the inbox host so the standalone package does not depend on a separately installed pwsh.
@(
    "if (-not (Get-Variable -Name IsWindows -Scope Global -ErrorAction SilentlyContinue)) { `$global:IsWindows = `$true }"
    Get-Content -LiteralPath $engineSource
) | Set-Content -LiteralPath $engineDestination -Encoding UTF8

$bundledEngine = Get-Content -LiteralPath $engineDestination -Raw
if ($bundledEngine.Contains("Join-Path `$ApplicationRoot 'extensions\update-runtime-dependencies.ps1'")) {
    throw '本物の更新エンジンではなく再帰ラッパーが混入しています'
}
if (-not $bundledEngine.Contains('function Resolve-AdoptiumRelease')) {
    throw '同梱した依存関係更新エンジンが不完全です'
}

$commonArguments = @(
    '--name', 'NicoCache_nl Updater',
    '--app-version', $AppVersion,
    '--input', $inputRoot,
    '--main-jar', 'NicoCacheUpdater.jar',
    '--main-class', 'dareka.updater.NicoCacheUpdater',
    '--dest', $outputRoot,
    '--vendor', 'NicoCache_nl',
    '--description', 'NicoCache_nl本体と外部依存関係を一元管理する独立アップデーター',
    '--verbose'
)

function Invoke-JPackage {
    param([Parameter(Mandatory)][string[]]$Arguments,
          [Parameter(Mandatory)][string]$FailureMessage)
    & $jpackage @Arguments 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

if ($PackageType -in @('AppImage', 'All')) {
    Invoke-JPackage -Arguments ($commonArguments + @('--type', 'app-image')) `
        -FailureMessage 'Updater AppImageの作成に失敗しました'
}
if ($PackageType -in @('Msi', 'All')) {
    Invoke-JPackage -Arguments ($commonArguments + @(
            '--type', 'msi', '--win-dir-chooser', '--win-menu', '--win-shortcut')) `
        -FailureMessage 'Updater MSIの作成に失敗しました'
}
Write-Host "成果物: $outputRoot"
