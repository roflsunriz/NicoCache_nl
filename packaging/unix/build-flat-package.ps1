#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,2}$')]
    [string]$AppVersion = '0.1.0',
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
    else { throw 'LinuxまたはmacOS上で実行してください' }
}
$hostPlatform = if ($IsLinux) { 'Linux' } elseif ($IsMacOS) { 'MacOS' } else { 'Other' }
if ($Platform -ne $hostPlatform) {
    throw "ネイティブパッケージは対象OS上で生成してください (host=$hostPlatform, target=$Platform)"
}
$allowed = if ($Platform -eq 'Linux') {
    @('AppImage', 'Zip', 'Deb', 'Rpm', 'All')
} else { @('AppImage', 'Zip', 'Pkg', 'Dmg', 'All') }
if ($PackageType -notin $allowed) { throw "$Platform では $PackageType を生成できません" }

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $root 'java-tool-selection.ps1')
$platformId = $Platform.ToLowerInvariant()
$workRoot = Join-Path $root (Join-Path '.test-work' "unix-package-$platformId")
$buildRoot = Join-Path $workRoot 'build'
$dependencyRoot = Join-Path $workRoot 'dependencies'
$artifactRoot = Join-Path $buildRoot 'artifacts'
$outputRoot = Join-Path $workRoot 'output'
$appImagePath = Join-Path $outputRoot 'NicoCache_nl'
$runtimeImage = Join-Path $buildRoot 'jre'
$architecture = switch ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64' { 'x64' }
    'Arm64' { 'arm64' }
    'X86' { 'x86' }
    default { throw '未対応のCPUアーキテクチャです' }
}

function Get-RequiredCommand {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "必要なコマンドが見つかりません: $Name" }
    return $(if ($command.Source) { $command.Source } else { $command.Path })
}
function Invoke-NativeCommand {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$FailureMessage)
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) { throw "$FailureMessage (ExitCode: $LASTEXITCODE)" }
}
function Get-RuntimeModules {
    param([string]$JavaPath)
    $resolution = @(& $JavaPath --show-module-resolution -version 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw '既定Javaモジュールを解決できませんでした' }
    $modules = @($resolution | ForEach-Object {
        if ($_ -match '^root (?<module>[A-Za-z0-9.]+) ') { $Matches.module }
    })
    $modules += @('jdk.charsets', 'java.desktop', 'java.net.http')
    return @($modules | Sort-Object -Unique) -join ','
}
function Copy-ApplicationRoot {
    param([string]$Destination)
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $appImagePath -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

if (Test-Path -LiteralPath $workRoot) {
    $resolved = (Resolve-Path -LiteralPath $workRoot).Path
    $testRoot = (Resolve-Path -LiteralPath (Join-Path $root '.test-work')).Path
    if (-not $resolved.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::Ordinal)) { throw "安全でない作業パスです: $resolved" }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Path $buildRoot, $dependencyRoot, $outputRoot | Out-Null
$java = Get-RequiredCommand 'java'
$jlink = Get-RequiredCommand 'jlink'
$javaMajor = (Assert-TemurinJavaRuntime -JavaPath $java -JavaVersion 25).Major
& (Join-Path $root 'packaging/windows/prepare-dependencies.ps1') `
    -DestinationDirectory $dependencyRoot
if ($LASTEXITCODE -ne 0) { throw '依存JARの準備に失敗しました' }
& (Join-Path $root 'build-javac.ps1') -JavaVersion $javaMajor `
    -LibraryDirectory $dependencyRoot -OutputDirectory $artifactRoot -Clean
if ($LASTEXITCODE -ne 0) { throw '配布JARのビルドに失敗しました' }
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
Invoke-NativeCommand $jlink @(
    '--module-path', (Join-Path $javaHome 'jmods'), '--add-modules',
    (Get-RuntimeModules $java), '--strip-debug', '--no-header-files',
    '--no-man-pages', '--output', $runtimeImage
) '同梱JREの作成に失敗しました'
& (Join-Path $root 'packaging/prepare-application-root.ps1') `
    -DestinationRoot $appImagePath -RuntimeImage $runtimeImage `
    -DependencyDirectory $dependencyRoot -ArtifactDirectory $artifactRoot `
    -AppVersion $AppVersion -Platform $Platform
if ($LASTEXITCODE -ne 0) { throw 'Unixアプリケーションルートの作成に失敗しました' }

if ($PackageType -in @('Zip', 'All')) {
    $zip = Get-RequiredCommand 'zip'
    $archive = Join-Path $outputRoot "NicoCache_nl-$AppVersion-$platformId-$architecture.zip"
    Push-Location $appImagePath
    try { Invoke-NativeCommand $zip @('-q', '-r', $archive, '.') 'ZIPの作成に失敗しました' }
    finally { Pop-Location }
}

if ($Platform -eq 'Linux' -and $PackageType -in @('Deb', 'All')) {
    $dpkg = Get-RequiredCommand 'dpkg-deb'
    $stage = Join-Path $buildRoot 'deb-root'
    $installRoot = Join-Path $stage 'opt/nicocache-nl'
    Copy-ApplicationRoot $installRoot
    $control = Join-Path $stage 'DEBIAN/control'
    New-Item -ItemType Directory -Path (Split-Path -Parent $control) -Force | Out-Null
    $debArch = switch ($architecture) { 'x64' { 'amd64' } 'arm64' { 'arm64' } default { 'i386' } }
    @(
        'Package: nicocache-nl'
        "Version: $AppVersion"
        "Architecture: $debArch"
        'Maintainer: NicoCache_nl maintainers'
        'Section: net'
        'Priority: optional'
        'Description: NicoCache_nl local HTTP/HTTPS proxy and cache server'
    ) | Set-Content -LiteralPath $control -Encoding utf8
    $bin = Join-Path $stage 'usr/bin'
    New-Item -ItemType Directory -Path $bin -Force | Out-Null
    & ln -s '/opt/nicocache-nl/NicoCache_nl' (Join-Path $bin 'nicocache-nl')
    if ($LASTEXITCODE -ne 0) { throw 'DEBの起動用シンボリックリンクを作成できません' }
    Invoke-NativeCommand $dpkg @('--build', '--root-owner-group', $stage,
        (Join-Path $outputRoot "NicoCache_nl-$AppVersion-linux-$architecture.deb")) `
        'DEBの作成に失敗しました'
}

if ($Platform -eq 'Linux' -and $PackageType -in @('Rpm', 'All')) {
    $rpmbuild = Get-RequiredCommand 'rpmbuild'
    $tar = Get-RequiredCommand 'tar'
    $top = Join-Path $buildRoot 'rpmbuild'
    foreach ($directory in @('BUILD', 'RPMS', 'SOURCES', 'SPECS', 'SRPMS')) {
        New-Item -ItemType Directory -Path (Join-Path $top $directory) -Force | Out-Null
    }
    $sourceStage = Join-Path $buildRoot 'NicoCache_nl'
    Copy-ApplicationRoot $sourceStage
    $sourceArchive = Join-Path $top 'SOURCES/NicoCache_nl.tar.gz'
    Invoke-NativeCommand $tar @('-czf', $sourceArchive, '-C', $buildRoot, 'NicoCache_nl') `
        'RPMソースアーカイブの作成に失敗しました'
    $rpmArchitecture = switch ($architecture) {
        'x64' { 'x86_64' }
        'arm64' { 'aarch64' }
        default { 'i686' }
    }
    $spec = @"
Name: nicocache-nl
Version: $AppVersion
Release: 1
Summary: NicoCache_nl local proxy and cache server
License: Apache-2.0
BuildArch: $rpmArchitecture
Source0: NicoCache_nl.tar.gz
%description
NicoCache_nl local HTTP/HTTPS proxy and cache server.
%prep
%setup -q -n NicoCache_nl
%install
mkdir -p %{buildroot}/opt/nicocache-nl %{buildroot}/usr/bin
cp -a . %{buildroot}/opt/nicocache-nl/
ln -s /opt/nicocache-nl/NicoCache_nl %{buildroot}/usr/bin/nicocache-nl
%files
/opt/nicocache-nl
/usr/bin/nicocache-nl
"@
    $specPath = Join-Path $top 'SPECS/nicocache-nl.spec'
    [IO.File]::WriteAllText($specPath, $spec, [Text.UTF8Encoding]::new($false))
    Invoke-NativeCommand $rpmbuild @('-bb', '--define', "_topdir $top", $specPath) `
        'RPMの作成に失敗しました'
    $rpm = Get-ChildItem -LiteralPath (Join-Path $top 'RPMS') -Recurse -File -Filter '*.rpm' |
        Select-Object -First 1
    if (-not $rpm) { throw 'RPM成果物がありません' }
    Copy-Item -LiteralPath $rpm.FullName -Destination (
        Join-Path $outputRoot "NicoCache_nl-$AppVersion-linux-$architecture.rpm")
}

if ($Platform -eq 'MacOS' -and $PackageType -in @('Pkg', 'All')) {
    $pkgbuild = Get-RequiredCommand 'pkgbuild'
    $stage = Join-Path $buildRoot 'pkg-root/Applications/NicoCache_nl'
    Copy-ApplicationRoot $stage
    Invoke-NativeCommand $pkgbuild @('--root', (Join-Path $buildRoot 'pkg-root'),
        '--identifier', 'jp.nicocache.nicocache-nl', '--version', $AppVersion,
        '--install-location', '/',
        (Join-Path $outputRoot "NicoCache_nl-$AppVersion-macos-$architecture.pkg")) `
        'PKGの作成に失敗しました'
}
if ($Platform -eq 'MacOS' -and $PackageType -in @('Dmg', 'All')) {
    $hdiutil = Get-RequiredCommand 'hdiutil'
    Invoke-NativeCommand $hdiutil @('create', '-volname', 'NicoCache_nl',
        '-srcfolder', $appImagePath, '-ov', '-format', 'UDZO',
        (Join-Path $outputRoot "NicoCache_nl-$AppVersion-macos-$architecture.dmg")) `
        'DMGの作成に失敗しました'
}

Get-ChildItem -LiteralPath $outputRoot -Force |
    Select-Object Name, FullName, Length | Format-Table -AutoSize
Write-Output "$Platform パッケージを作成しました: $outputRoot"
