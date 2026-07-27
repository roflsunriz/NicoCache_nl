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

if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot, $inputRoot, $outputRoot |
    Out-Null

$javac = (Get-Command javac -ErrorAction Stop).Source
$jar = (Get-Command jar -ErrorAction Stop).Source
$jpackage = (Get-Command jpackage -ErrorAction Stop).Source

$source = Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java'
& $javac --release 11 -encoding UTF-8 -d $classesRoot $source
if ($LASTEXITCODE -ne 0) {
    throw 'NicoCache_nl Updaterのコンパイルに失敗しました'
}

$manifest = Join-Path $workRoot 'manifest.mf'
@(
    'Manifest-Version: 1.0'
    'Main-Class: dareka.updater.NicoCacheUpdater'
    ''
) | Set-Content -LiteralPath $manifest -Encoding ascii

$jarPath = Join-Path $inputRoot 'NicoCacheUpdater.jar'
& $jar cfm $jarPath $manifest -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    throw 'NicoCache_nl UpdaterのJAR作成に失敗しました'
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
    '--win-menu',
    '--win-shortcut'
)

if ($PackageType -in @('AppImage', 'All')) {
    & $jpackage @commonArguments --type app-image
    if ($LASTEXITCODE -ne 0) {
        throw 'Updater AppImageの作成に失敗しました'
    }
}

if ($PackageType -in @('Msi', 'All')) {
    & $jpackage @commonArguments --type msi --win-dir-chooser
    if ($LASTEXITCODE -ne 0) {
        throw 'Updater MSIの作成に失敗しました'
    }
}

Write-Host "成果物: $outputRoot"
