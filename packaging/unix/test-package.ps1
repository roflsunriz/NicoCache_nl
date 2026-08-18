#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Linux', 'MacOS')]
    [string]$Platform,
    [ValidatePattern('^\d+(?:\.\d+){0,2}$')]
    [string]$AppVersion = '0.1.0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Platform)) {
    if ($IsLinux) { $Platform = 'Linux' }
    elseif ($IsMacOS) { $Platform = 'MacOS' }
    else { throw 'LinuxまたはmacOS上で実行してください' }
}
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$platformId = $Platform.ToLowerInvariant()
$workRoot = Join-Path $root (Join-Path '.test-work' "unix-package-$platformId")
$outputRoot = Join-Path $workRoot 'output'
$applicationRoot = (Resolve-Path -LiteralPath (Join-Path $outputRoot 'NicoCache_nl')).Path
$architecture = switch ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64' { 'x64' }
    'Arm64' { 'arm64' }
    'X86' { 'x86' }
    default { throw '未対応のCPUアーキテクチャです' }
}

foreach ($relative in @(
        'NicoCache_nl', 'NicoCacheDiagnostics', 'NicoCache_nl.jar',
        'NicoCacheCA.jar', 'NicoCacheLauncher.jar',
        'NicoCacheDiagnostics.jar', 'NicoCacheBuild.jar', 'NicoCache_nl.version',
        'jre/bin/java', 'jre/bin/jcmd', 'jre/lib/modules', 'lib/bcprov.jar', 'lib/bcpkix.jar',
        'lib/bcutil.jar', 'lib/brotli-dec.jar', 'lib/zstd-jni.jar',
        'documents/diagnostics-watchdog.md',
        'src/dareka/NLMain.java', 'build-javac.ps1',
        'tools/cmaf-to-mp4/nico-cmaf-to-mp4.jar'
    )) {
    $path = Join-Path $applicationRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "フラットなアプリケーションルートに必要なファイルがありません: $relative"
    }
}
if ((Get-Content -Raw -LiteralPath (Join-Path $applicationRoot 'NicoCache_nl.version')) -ne $AppVersion) {
    throw '配布版番号が一致しません'
}
$deletedPaths = @(& git -C $root -c core.quotePath=false ls-files --deleted)
foreach ($relativePath in @(& git -C $root -c core.quotePath=false ls-files --cached)) {
    if ($relativePath -in $deletedPaths) { continue }
    if (-not (Test-Path -LiteralPath (
            Join-Path $applicationRoot ($relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
        ) -PathType Leaf)) {
        throw "clone相当のアプリケーションルートにGit管理対象ファイルがありません: $relativePath"
    }
    $applicationFile = Join-Path $applicationRoot (
        $relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    $sourceFile = Join-Path $root (
        $relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    if ((Get-FileHash -LiteralPath $applicationFile -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash) {
        throw "アプリケーションルートのGit管理対象ファイルが作業ツリーと一致しません: $relativePath"
    }
}
foreach ($legacy in @('app', 'lib/app', 'lib/runtime', 'runtime', 'Contents')) {
    if (Test-Path -LiteralPath (Join-Path $applicationRoot $legacy)) {
        throw "jpackage固有の旧配置が残っています: $legacy"
    }
}
$java = Join-Path $applicationRoot 'jre/bin/java'
$launcherJar = Join-Path $applicationRoot 'NicoCacheLauncher.jar'
$help = @(& $java '-jar' $launcherJar '--help' 2>&1)
if ($LASTEXITCODE -ne 0 -or
        -not (($help -join "`n").Contains('--tray')) -or
        -not (($help -join "`n").Contains('--launcher-only-stop'))) {
    throw "同梱JREでNicoCacheLauncher.jarを起動できません: $help"
}
$diagnosticsHelp = @(& $java '-jar' (
        Join-Path $applicationRoot 'NicoCacheDiagnostics.jar') '--help' 2>&1)
if ($LASTEXITCODE -ne 0 -or
        -not (($diagnosticsHelp -join "`n").Contains('--collect-now'))) {
    throw "同梱JREでNicoCacheDiagnostics.jarを起動できません: $diagnosticsHelp"
}

$zip = Join-Path $outputRoot "NicoCache_nl-$AppVersion-$platformId-$architecture.zip"
if (-not (Test-Path -LiteralPath $zip -PathType Leaf)) { throw "ZIPがありません: $zip" }
$extract = Join-Path $workRoot 'zip-test'
if (Test-Path -LiteralPath $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
New-Item -ItemType Directory -Path $extract | Out-Null
& unzip -q $zip -d $extract
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (
        Join-Path $extract 'NicoCacheLauncher.jar'))) {
    throw 'ZIPのルートがアプリケーションルートと一致しません'
}

if ($Platform -eq 'Linux') {
    foreach ($entry in @(
            @{ Extension = '.deb'; Command = 'dpkg-deb'; Arguments = @('--contents') },
            @{ Extension = '.rpm'; Command = 'rpm'; Arguments = @('-qpl') }
        )) {
        $package = Join-Path $outputRoot "NicoCache_nl-$AppVersion-linux-$architecture$($entry.Extension)"
        if (-not (Test-Path -LiteralPath $package -PathType Leaf)) {
            throw "Linuxパッケージがありません: $package"
        }
        $packageArguments = @($entry.Arguments) + $package
        $listing = @(& $entry.Command @packageArguments 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or
                $listing -notmatch '/opt/nicocache-nl/NicoCacheLauncher\.jar' -or
                $listing -notmatch '/opt/nicocache-nl/jre/bin/java') {
            throw "Linuxパッケージがフラットなアプリケーションルートを含みません: $package"
        }
    }
} else {
    foreach ($extension in @('.pkg', '.dmg')) {
        $package = Join-Path $outputRoot "NicoCache_nl-$AppVersion-macos-$architecture$extension"
        if (-not (Test-Path -LiteralPath $package -PathType Leaf) -or
                (Get-Item -LiteralPath $package).Length -eq 0) {
            throw "macOSパッケージがありません: $package"
        }
    }
    $pkg = Join-Path $outputRoot "NicoCache_nl-$AppVersion-macos-$architecture.pkg"
    $listing = @(& pkgutil --payload-files $pkg 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or
            $listing -notmatch 'Applications/NicoCache_nl/NicoCacheLauncher\.jar' -or
            $listing -notmatch 'Applications/NicoCache_nl/jre/bin/java') {
        throw 'PKGがフラットなアプリケーションルートを含みません'
    }
}
Write-Output "$Platform package behavior tests passed"
