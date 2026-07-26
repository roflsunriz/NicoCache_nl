[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$AppVersion = '0.1.0',

    [ValidateSet('AppImage', 'Msi', 'All')]
    [string]$PackageType = 'AppImage',

    [string]$NlFiltersSource
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = Join-Path $root '.test-work'
$workRoot = Join-Path $testRoot 'windows-package'
$buildRoot = Join-Path $workRoot 'build'
$dependencyRoot = Join-Path $workRoot 'dependencies'
$inputRoot = Join-Path $workRoot 'input'
$outputRoot = Join-Path $workRoot 'output'
$appImagePath = Join-Path $outputRoot 'NicoCache_nl'
$packageIdentity = Import-PowerShellDataFile -LiteralPath (
    Join-Path $PSScriptRoot 'package-identity.psd1'
)
$upgradeUuid = [Guid]::Empty
if (-not [Guid]::TryParse(
        $packageIdentity.UpgradeUuid,
        [ref]$upgradeUuid
    ) -or $upgradeUuid -eq [Guid]::Empty) {
    throw 'package-identity.psd1 の UpgradeUuid が有効なUUIDではありません'
}
if ([string]::IsNullOrWhiteSpace($packageIdentity.MenuGroup)) {
    throw 'package-identity.psd1 の MenuGroup が空です'
}

function Assert-ChildPath {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Parent
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    if (-not $fullPath.StartsWith(
            $fullParent + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でない作業パスです: $fullPath"
    }
}

function Get-RequiredCommand {
    param([Parameter(Mandatory)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "必要なコマンドが見つかりません: $Name"
    }
    return $command.Source
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory)]
        [string]$FilePath,
        [Parameter(Mandatory)]
        [string[]]$ArgumentList,
        [Parameter(Mandatory)]
        [string]$FailureMessage
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (ExitCode: $LASTEXITCODE)"
    }
}

function Copy-DistributionDirectory {
    param(
        [Parameter(Mandatory)]
        [string]$RelativePath
    )

    $source = Join-Path $root $RelativePath
    $destination = Join-Path $inputRoot $RelativePath
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "配布元ディレクトリが見つかりません: $source"
    }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath $source -File | ForEach-Object {
        if (-not $_.LinkType) {
            Copy-Item -LiteralPath $_.FullName -Destination $destination
        }
    }
}

function Copy-DistributionFile {
    param(
        [Parameter(Mandatory)]
        [string]$RelativePath
    )

    $source = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "配布元ファイルが見つかりません: $source"
    }
    $destination = Join-Path $inputRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force |
        Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
}

Assert-ChildPath -Path $workRoot -Parent $testRoot
if (Test-Path -LiteralPath $workRoot) {
    $resolvedWorkRoot = (Resolve-Path -LiteralPath $workRoot).Path
    Assert-ChildPath -Path $resolvedWorkRoot -Parent $testRoot
    Remove-Item -LiteralPath $resolvedWorkRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $buildRoot, $dependencyRoot, $inputRoot,
    $outputRoot | Out-Null

$javac = Get-RequiredCommand -Name 'javac'
$jar = Get-RequiredCommand -Name 'jar'
$jpackage = Get-RequiredCommand -Name 'jpackage'
if ($PackageType -in @('Msi', 'All')) {
    Get-RequiredCommand -Name 'candle' | Out-Null
    Get-RequiredCommand -Name 'light' | Out-Null
}

$dependencyLock = Import-PowerShellDataFile -LiteralPath (
    Join-Path $PSScriptRoot 'dependency-lock.psd1'
)
foreach ($artifact in $dependencyLock.Artifacts) {
    $destination = Join-Path $dependencyRoot $artifact.FileName
    $download = "$destination.download"
    Invoke-WebRequest -Uri $artifact.Url -OutFile $download -UseBasicParsing
    $actualHash = (Get-FileHash -LiteralPath $download -Algorithm SHA256).
        Hash.ToLowerInvariant()
    if ($actualHash -ne $artifact.Sha256) {
        Remove-Item -LiteralPath $download -Force
        throw "依存ファイルのSHA-256が一致しません: $($artifact.Name)"
    }
    Move-Item -LiteralPath $download -Destination $destination
}

$mainClasses = Join-Path $buildRoot 'main-classes'
$caClasses = Join-Path $buildRoot 'ca-classes'
New-Item -ItemType Directory -Path $mainClasses, $caClasses | Out-Null

$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\dareka') `
        -Recurse -File -Filter '*.java' |
        Where-Object { $_.Name -ne 'package-info.java' } |
        Select-Object -ExpandProperty FullName)
Invoke-NativeCommand -FilePath $javac -ArgumentList (@(
        '--release', '11',
        '-encoding', 'UTF-8',
        '-Xlint:-options',
        '-d', $mainClasses
    ) + $mainSources) `
    -FailureMessage 'NicoCache_nl本体のコンパイルに失敗しました'
Copy-Item -LiteralPath (Join-Path $root 'src\dareka\GUILauncherIcon.gif') `
    -Destination (Join-Path $mainClasses 'dareka\GUILauncherIcon.gif')
Get-ChildItem -LiteralPath (Join-Path $root 'src\dareka') `
        -File -Filter 'setup_messages*.properties' |
    Copy-Item -Destination (Join-Path $mainClasses 'dareka')

$packageManifest = Join-Path $buildRoot 'manifest-package.mf'
@(
    'Manifest-Version: 1.0'
    'Main-Class: dareka.NLMain'
    'Class-Path: sqlite-jdbc.jar igo.jar library.jar NicoCacheCA.jar lib/bcpkix.jar lib/bcprov.jar lib/bcutil.jar'
    ''
) | Set-Content -LiteralPath $packageManifest -Encoding ascii
$mainJar = Join-Path $inputRoot 'NicoCache_nl.jar'
Invoke-NativeCommand -FilePath $jar -ArgumentList @(
    'cfm', $mainJar,
    $packageManifest,
    '-C', $mainClasses, 'dareka',
    '-C', (Join-Path $root 'src'), 'native'
) -FailureMessage 'NicoCache_nl.jarの作成に失敗しました'

$bcClasspath = ($dependencyLock.Artifacts | ForEach-Object {
        Join-Path $dependencyRoot $_.FileName
    }) -join [System.IO.Path]::PathSeparator
$caSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\nicocacheca') `
        -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName)
Invoke-NativeCommand -FilePath $javac -ArgumentList (@(
        '--release', '11',
        '-encoding', 'UTF-8',
        '-Xlint:-options',
        '-classpath', $bcClasspath,
        '-d', $caClasses
    ) + $caSources) `
    -FailureMessage 'NicoCacheCAのコンパイルに失敗しました'

$caManifest = Join-Path $buildRoot 'manifest-ca.mf'
@(
    'Manifest-Version: 1.0'
    'Main-Class: nicocacheca.NicoCacheCA'
    'Class-Path: lib/bcpkix.jar lib/bcprov.jar lib/bcutil.jar'
    ''
) | Set-Content -LiteralPath $caManifest -Encoding ascii
$caJar = Join-Path $inputRoot 'NicoCacheCA.jar'
Invoke-NativeCommand -FilePath $jar -ArgumentList @(
    'cfm', $caJar,
    $caManifest,
    '-C', $caClasses, 'nicocacheca'
) -FailureMessage 'NicoCacheCA.jarの作成に失敗しました'

$distributionFiles = @(
    'certificate-targets.txt',
    'config.properties.default',
    'NicoCacheGUI_native.dll',
    'NicoCacheGUI_native64.dll',
    'niconico-0.ico',
    'nlFilter_sys.txt',
    'proxy_sample.pac',
    'Readme.txt',
    'Readme_dms.txt',
    'ChangeLog.txt',
    '変更点.txt'
)
foreach ($relativePath in $distributionFiles) {
    Copy-DistributionFile -RelativePath $relativePath
}

$distributionDirectories = @('defaults', 'extensions', 'local')
foreach ($relativePath in $distributionDirectories) {
    Copy-DistributionDirectory -RelativePath $relativePath
}
$writableDirectories = @(
    'cache', 'certs', 'cvcache', 'data', 'list', 'nlFilters', 'thcache'
)
foreach ($relativePath in $writableDirectories) {
    New-Item -ItemType Directory -Path (Join-Path $inputRoot $relativePath) `
        -Force | Out-Null
}
foreach ($relativePath in @('certs\readme.txt', 'data\readme.txt',
        'data\cors\99_sample.conf', 'data\tlsclient\cacerts2',
        'list\NGtitle.txt')) {
    if (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf) {
        Copy-DistributionFile -RelativePath $relativePath
    }
}

if ($NlFiltersSource) {
    $resolvedFilters = (Resolve-Path -LiteralPath $NlFiltersSource).Path
    $filterFiles = @(Get-ChildItem -LiteralPath $resolvedFilters -File `
            -Filter '*.txt' |
            Where-Object {
                -not $_.LinkType -and $_.Name -match '^(0[1-9]|1[0-9]|20)(?:\D|$)'
            })
    if ($filterFiles.Count -eq 0) {
        throw "配布対象のnlFilters 01-20番台がありません: $resolvedFilters"
    }
    $filterFiles | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (
            Join-Path $inputRoot 'nlFilters'
        )
    }
}

$libDestination = Join-Path $inputRoot 'lib'
New-Item -ItemType Directory -Path $libDestination | Out-Null
foreach ($artifact in $dependencyLock.Artifacts) {
    Copy-Item -LiteralPath (Join-Path $dependencyRoot $artifact.FileName) `
        -Destination (Join-Path $libDestination $artifact.FileName)
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'THIRD-PARTY-NOTICES.txt') `
    -Destination (Join-Path $inputRoot 'THIRD-PARTY-NOTICES.txt')
$setupScriptDestination = Join-Path $inputRoot 'setup\windows'
New-Item -ItemType Directory -Path $setupScriptDestination -Force | Out-Null
$setupScriptSource =
    Join-Path $PSScriptRoot 'runtime\first-run-setup.ps1'
$setupScriptTarget =
    Join-Path $setupScriptDestination 'first-run-setup.ps1'
$setupScriptContent = [System.IO.File]::ReadAllText(
    $setupScriptSource,
    [System.Text.UTF8Encoding]::new($false, $true)
)
[System.IO.File]::WriteAllText(
    $setupScriptTarget,
    $setupScriptContent,
    [System.Text.UTF8Encoding]::new($true)
)

$sharedJavaOptions = @(
    '-Xmx128m',
    '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-exports=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-exports=java.base/jdk.internal.access=ALL-UNNAMED',
    '--add-exports=java.base/sun.nio.ch=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens=java.base/java.io=ALL-UNNAMED',
    '--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED'
)
$jpackageArguments = @(
    '--type', 'app-image',
    '--name', 'NicoCache_nl',
    '--app-version', $AppVersion,
    '--vendor', 'NicoCache_nl',
    '--description', 'ニコニコ動画向けローカルプロキシー兼キャッシュサーバー',
    '--input', $inputRoot,
    '--dest', $outputRoot,
    '--main-jar', 'NicoCache_nl.jar',
    '--main-class', 'dareka.NLMain',
    '--icon', (Join-Path $root 'niconico-0.ico')
)
foreach ($javaOption in $sharedJavaOptions) {
    $jpackageArguments += @('--java-options', $javaOption)
}
Invoke-NativeCommand -FilePath $jpackage -ArgumentList $jpackageArguments `
    -FailureMessage 'Windowsアプリイメージの作成に失敗しました'

$runtimeLayoutPaths = @(
    $distributionFiles
    $distributionDirectories
    $writableDirectories
    'setup'
    'THIRD-PARTY-NOTICES.txt'
)
foreach ($relativePath in $runtimeLayoutPaths) {
    $source = Join-Path (Join-Path $appImagePath 'app') $relativePath
    if (-not (Test-Path -LiteralPath $source)) {
        continue
    }
    $destination = Join-Path $appImagePath $relativePath
    if (Test-Path -LiteralPath $destination) {
        throw "アプリ実行時配置先が既に存在します: $destination"
    }
    Move-Item -LiteralPath $source -Destination $destination
}

if ($PackageType -in @('Msi', 'All')) {
    $msiArguments = @(
        '--type', 'msi',
        '--name', 'NicoCache_nl',
        '--app-version', $AppVersion,
        '--vendor', 'NicoCache_nl',
        '--description', 'ニコニコ動画向けローカルプロキシー兼キャッシュサーバー',
        '--app-image', $appImagePath,
        '--dest', $outputRoot,
        '--win-per-user-install',
        '--win-menu',
        '--win-menu-group', $packageIdentity.MenuGroup,
        '--win-shortcut-prompt',
        '--win-upgrade-uuid', $upgradeUuid.ToString(),
        '--win-dir-chooser'
    )
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList $msiArguments `
        -FailureMessage 'Windows MSIの作成に失敗しました'
}

$artifacts = @(Get-ChildItem -LiteralPath $outputRoot -Force |
    Select-Object Name, FullName, Length)
$artifacts | Format-Table -AutoSize
Write-Output "Windowsパッケージ試作を作成しました: $outputRoot"
