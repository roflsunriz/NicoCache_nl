#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,2}$')]
    [string]$AppVersion = '0.2.1',

    [ValidateSet('Linux', 'MacOS')]
    [string]$Platform,

    [ValidateSet('AppImage', 'Zip', 'Deb', 'Rpm', 'Pkg', 'Dmg', 'All')]
    [string]$PackageType = 'AppImage'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Platform)) {
    if ($IsLinux) { $Platform = 'Linux' }
    elseif ($IsMacOS) { $Platform = 'MacOS' }
    else { throw 'LinuxまたはmacOS上で実行するか、-Platformを指定してください' }
}
$hostPlatform = if ($IsLinux) { 'Linux' } elseif ($IsMacOS) { 'MacOS' } else { 'Other' }
if ($Platform -ne $hostPlatform) {
    throw "ネイティブパッケージは対象OS上で生成してください (host=$hostPlatform, target=$Platform)"
}
$allowedTypes = if ($Platform -eq 'Linux') {
    @('AppImage', 'Zip', 'Deb', 'Rpm', 'All')
} else {
    @('AppImage', 'Zip', 'Pkg', 'Dmg', 'All')
}
if ($PackageType -ne 'All' -and $allowedTypes -notcontains $PackageType) {
    throw "$Platform では -PackageType $PackageType を生成できません"
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $root 'java-tool-selection.ps1')
$workRoot = Join-Path $root (Join-Path '.test-work' ('standalone-updater-' + $Platform.ToLowerInvariant()))
$classesRoot = Join-Path $workRoot 'classes'
$inputRoot = Join-Path $workRoot 'input'
$outputRoot = Join-Path $workRoot 'output'
$buildLog = Join-Path $workRoot 'jpackage.log'
$architecture = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64' { 'x64' }
    'Arm64' { 'arm64' }
    'X86' { 'x86' }
    default { throw '未対応のCPUアーキテクチャです' }
}
$platformId = $Platform.ToLowerInvariant()
$bundleName = 'NicoCache_nl Updater' + $(if ($Platform -eq 'MacOS') { '.app' } else { '' })
$appImagePath = Join-Path $outputRoot $bundleName

function Get-JPackageVersion {
    if ($Platform -ne 'MacOS') { return $AppVersion }
    $parts = @($AppVersion.Split('.'))
    if ([int]$parts[0] -gt 0) { return $AppVersion }

    # macOS jpackage rejects a zero major version. The public updater asset
    # name remains AppVersion; this value is only bundle metadata.
    $mappedParts = @('1')
    if ($parts.Count -gt 1) {
        $mappedParts += $parts[1..($parts.Count - 1)]
    }
    return ($mappedParts -join '.')
}

$jpackageVersion = Get-JPackageVersion
if ($jpackageVersion -ne $AppVersion) {
    Write-Output "macOSのjpackage内部版を $AppVersion から $jpackageVersion へ変換します"
}

function Assert-ChildPath {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Parent)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    if (-not $fullPath.StartsWith($fullParent + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でない作業パスです: $fullPath"
    }
}

function Get-RequiredCommand {
    param([Parameter(Mandatory)][string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "必要なコマンドが見つかりません: $Name" }
    if ($command.Source) { return $command.Source }
    return $command.Path
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][string]$FailureMessage
    )
    & $FilePath @ArgumentList 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw "$FailureMessage (ExitCode: $LASTEXITCODE)" }
}

function Rename-NativeArtifact {
    param([Parameter(Mandatory)][string]$Extension)
    $candidate = Get-ChildItem -LiteralPath $outputRoot -Filter ("*" + $Extension) -File |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) { throw "jpackageの${Extension}成果物が見つかりません" }
    $target = Join-Path $outputRoot ("NicoCache_nl-Updater-$AppVersion-$platformId-$architecture$Extension")
    Move-Item -LiteralPath $candidate.FullName -Destination $target -Force
    Write-Output "ネイティブパッケージを作成しました: $target"
}

function Get-PackageArguments {
    param([Parameter(Mandatory)][string]$Type)
    $arguments = @(
        '-J-Duser.language=ja', '-J-Duser.country=JP', '--type', $Type,
        '--name', 'NicoCache_nl Updater', '--app-version', $jpackageVersion,
        '--vendor', 'NicoCache_nl',
        '--description', 'NicoCache_nl本体と外部依存関係を管理するアップデーター',
        '--app-image', $appImagePath, '--dest', $outputRoot, '--verbose'
    )
    if ($Platform -eq 'Linux') {
        $arguments += @('--linux-package-name', 'nicocache-nl-updater', '--linux-app-category', 'Network')
        if ($Type -eq 'deb') {
            $arguments += @('--linux-deb-maintainer', 'maintainers@nicocache.invalid', '--linux-shortcut')
        } elseif ($Type -eq 'rpm') {
            $arguments += @('--linux-rpm-license-type', 'Apache-2.0', '--linux-shortcut')
        }
    } else {
        $arguments += @('--mac-package-identifier', 'jp.nicocache.nicocache-nl.updater')
    }
    return $arguments
}

Assert-ChildPath -Path $workRoot -Parent (Join-Path $root '.test-work')
if (Test-Path -LiteralPath $workRoot) {
    $resolvedWorkRoot = (Resolve-Path -LiteralPath $workRoot).Path
    Assert-ChildPath -Path $resolvedWorkRoot -Parent (Join-Path $root '.test-work')
    Remove-Item -LiteralPath $resolvedWorkRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot, $inputRoot, $outputRoot | Out-Null

$java = Get-RequiredCommand -Name 'java'
$javac = Get-RequiredCommand -Name 'javac'
$jar = Get-RequiredCommand -Name 'jar'
$jpackage = Get-RequiredCommand -Name 'jpackage'
$zip = Get-RequiredCommand -Name 'zip'
Assert-TemurinJavaRuntime -JavaPath $java -JavaVersion 25 | Out-Null
if ($Platform -eq 'Linux' -and $PackageType -in @('Rpm', 'All')) {
    Get-RequiredCommand -Name 'rpmbuild' | Out-Null
}

$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'updater/src') -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)
if ($sources.Count -lt 6) { throw 'Pure Java updater sources are incomplete' }
Invoke-NativeCommand -FilePath $javac -ArgumentList (@('--release', '11', '-encoding', 'UTF-8',
        '-Xlint:all', '-d', $classesRoot) + $sources) -FailureMessage 'アップデーターのコンパイルに失敗しました'

$manifest = Join-Path $workRoot 'manifest.mf'
@('Manifest-Version: 1.0', 'Main-Class: dareka.updater.UpdaterLauncher', '') |
    Set-Content -LiteralPath $manifest -Encoding ascii
$jarPath = Join-Path $inputRoot 'NicoCacheUpdater.jar'
Invoke-NativeCommand -FilePath $jar -ArgumentList @('cfm', $jarPath, $manifest,
        '-C', $classesRoot, '.') -FailureMessage 'アップデーターJARの作成に失敗しました'

$appImageArguments = @(
    '-J-Duser.language=ja', '-J-Duser.country=JP', '--type', 'app-image',
    '--name', 'NicoCache_nl Updater', '--app-version', $jpackageVersion,
    '--vendor', 'NicoCache_nl',
    '--description', 'NicoCache_nl本体と外部依存関係を管理するアップデーター',
    '--input', $inputRoot, '--dest', $outputRoot,
    '--main-jar', 'NicoCacheUpdater.jar', '--main-class', 'dareka.updater.UpdaterLauncher'
)
if ($Platform -eq 'Linux') {
    $appImageArguments += @('--icon', (Join-Path $root 'packaging/windows/assets/nicocache-updater.png'))
}
Invoke-NativeCommand -FilePath $jpackage -ArgumentList $appImageArguments `
    -FailureMessage "${Platform}向け独立アップデーターアプリイメージの作成に失敗しました"

$archivePath = Join-Path $outputRoot ("NicoCache_nl-Updater-$AppVersion-$platformId-$architecture.zip")
if ($PackageType -in @('Zip', 'All')) {
    Push-Location $outputRoot
    try {
        & $zip -q -r $archivePath $bundleName
        if ($LASTEXITCODE -ne 0) { throw "アップデーターZIPの作成に失敗しました (ExitCode: $LASTEXITCODE)" }
    } finally { Pop-Location }
    Write-Output "アップデーターZIPを作成しました: $archivePath"
}

if ($Platform -eq 'Linux' -and $PackageType -in @('Deb', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-PackageArguments -Type 'deb') `
        -FailureMessage 'LinuxアップデーターDEBの作成に失敗しました'
    Rename-NativeArtifact -Extension '.deb'
}
if ($Platform -eq 'Linux' -and $PackageType -in @('Rpm', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-PackageArguments -Type 'rpm') `
        -FailureMessage 'LinuxアップデーターRPMの作成に失敗しました'
    Rename-NativeArtifact -Extension '.rpm'
}
if ($Platform -eq 'MacOS' -and $PackageType -in @('Pkg', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-PackageArguments -Type 'pkg') `
        -FailureMessage 'macOSアップデーターPKGの作成に失敗しました'
    Rename-NativeArtifact -Extension '.pkg'
}
if ($Platform -eq 'MacOS' -and $PackageType -in @('Dmg', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-PackageArguments -Type 'dmg') `
        -FailureMessage 'macOSアップデーターDMGの作成に失敗しました'
    Rename-NativeArtifact -Extension '.dmg'
}

Get-ChildItem -LiteralPath $outputRoot -Force | Select-Object Name, FullName, Length |
    Format-Table -AutoSize
Write-Output "${Platform}向け独立アップデーターを作成しました: $outputRoot"
