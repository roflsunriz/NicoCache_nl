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
$workRoot = Join-Path $root (Join-Path '.test-work' ('unix-package-' + $Platform.ToLowerInvariant()))
$buildRoot = Join-Path $workRoot 'build'
$dependencyRoot = Join-Path $workRoot 'dependencies'
$inputRoot = Join-Path $workRoot 'input'
$outputRoot = Join-Path $workRoot 'output'
$buildLog = Join-Path $workRoot 'jpackage.log'
$dependencyLockPath = Join-Path $root 'packaging/windows/dependency-lock.psd1'
$dependencyLock = Import-PowerShellDataFile -LiteralPath $dependencyLockPath
$architecture = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64' { 'x64' }
    'Arm64' { 'arm64' }
    'X86' { 'x86' }
    default { throw '未対応のCPUアーキテクチャです' }
}
$platformId = $Platform.ToLowerInvariant()
$bundleName = 'NicoCache_nl' + $(if ($Platform -eq 'MacOS') { '.app' } else { '' })
$appImagePath = Join-Path $outputRoot $bundleName
$contentRoot = if ($Platform -eq 'MacOS') { Join-Path $appImagePath 'Contents' } else { $appImagePath }
$resourceDirectory = if ($Platform -eq 'MacOS') {
    Join-Path $contentRoot 'Resources'
} else {
    $contentRoot
}
$applicationDirectory = if ($Platform -eq 'Linux') {
    Join-Path $contentRoot 'lib/app'
} else {
    Join-Path $contentRoot 'app'
}

function Get-JPackageVersion {
    if ($Platform -ne 'MacOS') { return $AppVersion }
    $parts = @($AppVersion.Split('.'))
    if ([int]$parts[0] -gt 0) { return $AppVersion }

    # macOS jpackage rejects a zero major version. Keep the public release
    # version in NicoCache_nl.version and use a valid bundle version only for
    # Apple's package metadata.
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

function Get-JavaMajorVersion {
    param([Parameter(Mandatory)][string]$JavaPath)
    $properties = @(& $JavaPath -XshowSettings:properties -version 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw 'Javaのバージョン情報を取得できませんでした' }
    $line = $properties | Where-Object { $_ -match '^\s*java\.specification\.version\s*=' } |
        Select-Object -First 1
    $match = [regex]::Match([string]$line, 'java\.specification\.version\s*=\s*(?<major>\d+)')
    if (-not $match.Success) { throw 'Javaのメジャーバージョンを判定できませんでした' }
    return [int]$match.Groups['major'].Value
}

function Copy-DistributionFile {
    param([Parameter(Mandatory)][string]$RelativePath)
    $source = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "配布元ファイルが見つかりません: $source"
    }
    $sourceItem = Get-Item -LiteralPath $source -Force
    if ($sourceItem.LinkType) { throw "シンボリックリンクはUnix配布物へ含められません: $source" }
    $destination = Join-Path $inputRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
}

function Copy-DistributionDirectory {
    param([Parameter(Mandatory)][string]$RelativePath)
    $source = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "配布元ディレクトリが見つかりません: $source"
    }
    $destination = Join-Path $inputRoot $RelativePath
    Get-ChildItem -LiteralPath $source -Recurse -File -Force | ForEach-Object {
        if ($_.LinkType) { return }
        $relative = [System.IO.Path]::GetRelativePath($source, $_.FullName)
        $target = Join-Path $destination $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
}

function Get-RuntimeModules {
    param([Parameter(Mandatory)][string]$JavaPath)
    $resolution = @(& $JavaPath --show-module-resolution -version 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw '既定Javaモジュールを解決できませんでした' }
    $modules = @($resolution | ForEach-Object {
        if ($_ -match '^root (?<module>[A-Za-z0-9.]+) ') { $Matches.module }
    } | Sort-Object -Unique)
    $modules += 'jdk.charsets'
    $modules = @($modules | Sort-Object -Unique)
    if ($modules.Count -le 1) { throw '既定Javaモジュールの一覧が空です' }
    return $modules -join ','
}

function Get-ProductPackageArguments {
    param([Parameter(Mandatory)][string]$Type)
    $arguments = @(
        '-J-Duser.language=ja', '-J-Duser.country=JP',
        '--type', $Type, '--name', 'NicoCache_nl', '--app-version', $jpackageVersion,
        '--vendor', 'NicoCache_nl',
        '--description', 'ニコニコ動画向けローカルプロキシー兼キャッシュサーバー',
        '--app-image', $appImagePath, '--dest', $outputRoot, '--verbose'
    )
    if ($Platform -eq 'Linux') {
        $arguments += @('--linux-package-name', 'nicocache-nl', '--linux-app-category', 'Network')
        if ($Type -eq 'deb') {
            $arguments += @('--linux-deb-maintainer', 'maintainers@nicocache.invalid', '--linux-shortcut')
        } elseif ($Type -eq 'rpm') {
            $arguments += @('--linux-rpm-license-type', 'Apache-2.0', '--linux-shortcut')
        }
    } else {
        $arguments += @('--mac-package-identifier', 'jp.nicocache.nicocache-nl')
    }
    return $arguments
}

function Rename-NativeArtifact {
    param([Parameter(Mandatory)][string]$Extension)
    $candidate = Get-ChildItem -LiteralPath $outputRoot -Filter ("*" + $Extension) -File |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) { throw "jpackageの${Extension}成果物が見つかりません" }
    $target = Join-Path $outputRoot ("NicoCache_nl-$AppVersion-$platformId-$architecture$Extension")
    Move-Item -LiteralPath $candidate.FullName -Destination $target -Force
    Write-Output "ネイティブパッケージを作成しました: $target"
}

Assert-ChildPath -Path $workRoot -Parent (Join-Path $root '.test-work')
if (Test-Path -LiteralPath $workRoot) {
    $resolvedWorkRoot = (Resolve-Path -LiteralPath $workRoot).Path
    Assert-ChildPath -Path $resolvedWorkRoot -Parent (Join-Path $root '.test-work')
    Remove-Item -LiteralPath $resolvedWorkRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $buildRoot, $dependencyRoot, $inputRoot, $outputRoot | Out-Null

$java = Get-RequiredCommand -Name 'java'
$javac = Get-RequiredCommand -Name 'javac'
$jar = Get-RequiredCommand -Name 'jar'
$jpackage = Get-RequiredCommand -Name 'jpackage'
$zip = Get-RequiredCommand -Name 'zip'
$javaMajor = Get-JavaMajorVersion -JavaPath $java
if ($javaMajor -ne 25) { throw 'Linux/macOSパッケージのビルドにはJDK 25が必要です' }
if ($Platform -eq 'Linux' -and $PackageType -in @('Rpm', 'All')) {
    Get-RequiredCommand -Name 'rpmbuild' | Out-Null
}

foreach ($artifact in $dependencyLock.Artifacts) {
    $destination = Join-Path $dependencyRoot $artifact.FileName
    $partial = "$destination.download"
    Invoke-WebRequest -Uri $artifact.Url -OutFile $partial
    $actualHash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $artifact.Sha256) {
        Remove-Item -LiteralPath $partial -Force
        throw "依存ファイルのSHA-256が一致しません: $($artifact.Name)"
    }
    Move-Item -LiteralPath $partial -Destination $destination
}

$mainClasses = Join-Path $buildRoot 'main-classes'
$caClasses = Join-Path $buildRoot 'ca-classes'
New-Item -ItemType Directory -Path $mainClasses, $caClasses | Out-Null
$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src/dareka') -Recurse -File -Filter '*.java' |
    Where-Object { $_.Name -ne 'package-info.java' } | Select-Object -ExpandProperty FullName)
Invoke-NativeCommand -FilePath $javac -ArgumentList (@('--release', '11', '-encoding', 'UTF-8',
        '-Xlint:-options', '-d', $mainClasses) + $mainSources) `
    -FailureMessage 'NicoCache_nl本体のコンパイルに失敗しました'
Copy-Item -LiteralPath (Join-Path $root 'src/dareka/GUILauncherIcon.gif') -Destination (
    Join-Path $mainClasses 'dareka/GUILauncherIcon.gif')
Get-ChildItem -LiteralPath (Join-Path $root 'src/dareka') -File -Filter 'setup_messages*.properties' |
    Copy-Item -Destination (Join-Path $mainClasses 'dareka')

$packageManifest = Join-Path $buildRoot 'manifest-package.mf'
@(
    'Manifest-Version: 1.0'
    'Main-Class: dareka.UserDataMain'
    'Class-Path: NicoCacheCA.jar lib/bcpkix.jar lib/bcprov.jar lib/bcutil.jar'
    ''
) | Set-Content -LiteralPath $packageManifest -Encoding ascii
$mainJar = Join-Path $inputRoot 'NicoCache_nl.jar'
Invoke-NativeCommand -FilePath $jar -ArgumentList @('cfm', $mainJar, $packageManifest,
        '-C', $mainClasses, 'dareka', '-C', (Join-Path $root 'src'), 'native') `
    -FailureMessage 'NicoCache_nl.jarの作成に失敗しました'

$bcClasspath = ($dependencyLock.Artifacts | ForEach-Object {
    Join-Path $dependencyRoot $_.FileName
}) -join [System.IO.Path]::PathSeparator
$caSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src/nicocacheca') -File -Filter '*.java' |
    Select-Object -ExpandProperty FullName)
Invoke-NativeCommand -FilePath $javac -ArgumentList (@('--release', '11', '-encoding', 'UTF-8',
        '-Xlint:-options', '-classpath', $bcClasspath, '-d', $caClasses) + $caSources) `
    -FailureMessage 'NicoCacheCAのコンパイルに失敗しました'
$caManifest = Join-Path $buildRoot 'manifest-ca.mf'
@('Manifest-Version: 1.0', 'Main-Class: nicocacheca.NicoCacheCA',
    'Class-Path: lib/bcpkix.jar lib/bcprov.jar lib/bcutil.jar', '') |
    Set-Content -LiteralPath $caManifest -Encoding ascii
$caJar = Join-Path $inputRoot 'NicoCacheCA.jar'
Invoke-NativeCommand -FilePath $jar -ArgumentList @('cfm', $caJar, $caManifest,
        '-C', $caClasses, 'nicocacheca') -FailureMessage 'NicoCacheCA.jarの作成に失敗しました'

foreach ($file in @('certificate-targets.txt', 'config.properties.default', 'nlFilter_sys.txt',
        'proxy_sample.pac', 'README.md', 'CHANGELOG.md', 'documents/tls.md')) {
    Copy-DistributionFile -RelativePath $file
}
Copy-DistributionDirectory -RelativePath 'defaults'
foreach ($relativePath in (Get-Content -LiteralPath (Join-Path $root 'packaging/system-files.txt') |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })) {
    Copy-DistributionFile -RelativePath $relativePath
}
foreach ($relativePath in @('data/readme.txt', 'data/cors/99_sample.conf', 'data/tlsclient/cacerts2')) {
    Copy-DistributionFile -RelativePath $relativePath
}
New-Item -ItemType Directory -Path (Join-Path $inputRoot 'data'), (Join-Path $inputRoot 'list') -Force | Out-Null
$libDestination = Join-Path $inputRoot 'lib'
New-Item -ItemType Directory -Path $libDestination -Force | Out-Null
foreach ($artifact in $dependencyLock.Artifacts) {
    Copy-Item -LiteralPath (Join-Path $dependencyRoot $artifact.FileName) -Destination $libDestination
}
Copy-Item -LiteralPath (Join-Path $root 'packaging/windows/THIRD-PARTY-NOTICES.txt') `
    -Destination (Join-Path $inputRoot 'THIRD-PARTY-NOTICES.txt')
$cmafPackageScript = Join-Path $root 'tools/cmaf-to-mp4/prepare-package.ps1'
if (-not (Test-Path -LiteralPath $cmafPackageScript -PathType Leaf)) {
    throw "CMAF/Domand変換アプリのパッケージ準備スクリプトが見つかりません: $cmafPackageScript"
}
& $cmafPackageScript -DestinationRoot $inputRoot
if ($LASTEXITCODE -ne 0) {
    throw "CMAF/Domand変換アプリを${Platform}パッケージへ追加できません (ExitCode: $LASTEXITCODE)"
}

$sharedJavaOptions = @(
    '-Xmx128m', '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-exports=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.access=ALL-UNNAMED',
    '--add-exports=java.base/sun.nio.ch=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens=java.base/java.io=ALL-UNNAMED',
    '--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED'
)
$appImageArguments = @(
    '-J-Duser.language=ja', '-J-Duser.country=JP', '--type', 'app-image',
    '--add-modules', (Get-RuntimeModules -JavaPath $java), '--name', 'NicoCache_nl',
    '--app-version', $jpackageVersion, '--vendor', 'NicoCache_nl',
    '--description', 'ニコニコ動画向けローカルプロキシー兼キャッシュサーバー',
    '--input', $inputRoot, '--dest', $outputRoot,
    '--main-jar', 'NicoCache_nl.jar', '--main-class', 'dareka.UserDataMain'
)
if ($Platform -eq 'Linux') {
    $appImageArguments += @('--icon', (Join-Path $root 'packaging/windows/assets/nicocache-installer.png'))
}
foreach ($javaOption in $sharedJavaOptions) { $appImageArguments += @('--java-options', $javaOption) }
Invoke-NativeCommand -FilePath $jpackage -ArgumentList $appImageArguments `
    -FailureMessage "${Platform}アプリイメージの作成に失敗しました"

$runtimeLayoutPaths = @(
    'certificate-targets.txt', 'config.properties.default', 'nlFilter_sys.txt',
    'proxy_sample.pac', 'README.md', 'CHANGELOG.md', 'documents/tls.md', 'defaults',
    'data', 'list', 'extensions', 'local', 'nlFilters',
    'tools',
    'THIRD-PARTY-NOTICES.txt'
)
New-Item -ItemType Directory -Path $resourceDirectory -Force | Out-Null
foreach ($relativePath in $runtimeLayoutPaths) {
    $source = Join-Path $applicationDirectory $relativePath
    if (-not (Test-Path -LiteralPath $source)) { continue }
    $destination = Join-Path $resourceDirectory $relativePath
    if (Test-Path -LiteralPath $destination) { throw "アプリ実行時配置先が既に存在します: $destination" }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
}

Set-Content -LiteralPath (Join-Path $resourceDirectory 'NicoCache_nl.version') `
    -Value $AppVersion -Encoding ascii -NoNewline

if ($PackageType -in @('Zip', 'All')) {
    $archivePath = Join-Path $outputRoot ("NicoCache_nl-$AppVersion-$platformId-$architecture.zip")
    Push-Location $outputRoot
    try {
        & $zip -q -r $archivePath $bundleName
        if ($LASTEXITCODE -ne 0) { throw "ZIPの作成に失敗しました (ExitCode: $LASTEXITCODE)" }
    } finally { Pop-Location }
    Write-Output "ZIPを作成しました: $archivePath"
}

if ($Platform -eq 'Linux' -and $PackageType -in @('Deb', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-ProductPackageArguments -Type 'deb') `
        -FailureMessage 'Linux DEBの作成に失敗しました'
    Rename-NativeArtifact -Extension '.deb'
}
if ($Platform -eq 'Linux' -and $PackageType -in @('Rpm', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-ProductPackageArguments -Type 'rpm') `
        -FailureMessage 'Linux RPMの作成に失敗しました'
    Rename-NativeArtifact -Extension '.rpm'
}
if ($Platform -eq 'MacOS' -and $PackageType -in @('Pkg', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-ProductPackageArguments -Type 'pkg') `
        -FailureMessage 'macOS PKGの作成に失敗しました'
    Rename-NativeArtifact -Extension '.pkg'
}
if ($Platform -eq 'MacOS' -and $PackageType -in @('Dmg', 'All')) {
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList (Get-ProductPackageArguments -Type 'dmg') `
        -FailureMessage 'macOS DMGの作成に失敗しました'
    Rename-NativeArtifact -Extension '.dmg'
}

Get-ChildItem -LiteralPath $outputRoot -Force | Select-Object Name, FullName, Length |
    Format-Table -AutoSize
Write-Output "${Platform}パッケージを作成しました: $outputRoot"
