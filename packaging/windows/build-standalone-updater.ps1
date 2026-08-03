[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$AppVersion = '0.2.1',

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
$icon = Join-Path $root 'packaging\windows\assets\nicocache-updater.ico'
$description = 'NicoCache_nl updater for the application and external dependencies'

if (Test-Path -LiteralPath $workRoot) {
    Remove-Item -LiteralPath $workRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot, $inputRoot, $outputRoot | Out-Null
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) {
    throw "独立アップデーター用アイコンが見つかりません: $icon"
}

$java = (Get-Command java -ErrorAction Stop).Source
$javac = (Get-Command javac -ErrorAction Stop).Source
$jar = (Get-Command jar -ErrorAction Stop).Source
$jpackage = (Get-Command jpackage -ErrorAction Stop).Source

$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'updater\src') -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)
if ($sources.Count -lt 4) {
    throw 'Pure Java updater sources are incomplete'
}
& $javac --release 11 -encoding UTF-8 -Xlint:all -d $classesRoot @sources
if ($LASTEXITCODE -ne 0) {
    throw 'NicoCache_nl Updaterのコンパイルに失敗しました'
}

$manifest = Join-Path $workRoot 'manifest.mf'
@(
    'Manifest-Version: 1.0'
    'Main-Class: dareka.updater.UpdaterLauncher'
    ''
) | Set-Content -LiteralPath $manifest -Encoding ascii

$jarPath = Join-Path $inputRoot 'NicoCacheUpdater.jar'
& $jar cfm $jarPath $manifest -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    throw 'NicoCache_nl UpdaterのJAR作成に失敗しました'
}

$forbidden = @(Get-ChildItem -LiteralPath $inputRoot -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object Extension -in @('.ps1', '.psd1', '.psm1'))
if ($forbidden.Count -ne 0) {
    $forbiddenNames = @($forbidden | ForEach-Object FullName) -join ', '
    throw "PowerShell files leaked into updater input: $forbiddenNames"
}

$commonArguments = @(
    '-J-Duser.language=ja',
    '-J-Duser.country=JP',
    '--name', 'NicoCache_nl Updater',
    '--app-version', $AppVersion,
    '--input', $inputRoot,
    '--main-jar', 'NicoCacheUpdater.jar',
    '--main-class', 'dareka.updater.UpdaterLauncher',
    '--dest', $outputRoot,
    '--vendor', 'NicoCache_nl',
    # Keep Windows version-resource metadata ASCII so Task Manager does not replace
    # the description with question marks on an English system.
    '--description', $description,
    '--icon', $icon,
    '--verbose'
)

function Invoke-JPackage {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$FailureMessage
    )
    & $jpackage @Arguments 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

$versionProperties = @(
    & $java -XshowSettings:properties -version 2>&1 |
        ForEach-Object { [string]$_ }
)
if ($LASTEXITCODE -ne 0) {
    throw 'Javaのバージョン情報を取得できませんでした'
}
$versionLine = $versionProperties |
    Where-Object { $_ -match '^\s*java\.specification\.version\s*=' } |
    Select-Object -First 1
$versionMatch = [regex]::Match(
    [string]$versionLine,
    'java\.specification\.version\s*=\s*(?<major>\d+)'
)
if (-not $versionMatch.Success -or [int]$versionMatch.Groups['major'].Value -ne 25) {
    throw '独立アップデーターのビルドにはJDK 25が必要です'
}

if ($PackageType -in @('AppImage', 'All')) {
    Invoke-JPackage -Arguments ($commonArguments + @('--type', 'app-image')) `
        -FailureMessage 'Updater AppImageの作成に失敗しました'
}

if ($PackageType -in @('Msi', 'All')) {
    Invoke-JPackage -Arguments ($commonArguments + @(
            '--type', 'msi',
            '--win-per-user-install',
            '--win-dir-chooser',
            '--win-menu',
            '--win-shortcut'
        )) -FailureMessage 'Updater MSIの作成に失敗しました'
}

Write-Host "成果物: $outputRoot"
