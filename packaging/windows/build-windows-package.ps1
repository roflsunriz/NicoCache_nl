[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$AppVersion = '0.1.0',

    [ValidateSet('AppImage', 'Zip', 'Msi', 'All')]
    [string]$PackageType = 'AppImage',

    [string]$ZipFileName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$launcherIcon = Join-Path $root 'packaging\windows\assets\nicocache-launcher.ico'
if (-not (Test-Path -LiteralPath $launcherIcon -PathType Leaf)) {
    throw "本体ランチャー用アイコンが見つかりません: $launcherIcon"
}
$testRoot = Join-Path $root '.test-work'
$workRoot = Join-Path $testRoot 'windows-package'
$buildRoot = Join-Path $workRoot 'build'
$dependencyRoot = Join-Path $workRoot 'dependencies'
$inputRoot = Join-Path $workRoot 'input'
$outputRoot = Join-Path $workRoot 'output'
$msiTempRoot = Join-Path $workRoot 'jpackage-msi'
$msiProbeOutputRoot = Join-Path $workRoot 'msi-probe-output'
$msiProbeTempRoot = Join-Path $workRoot 'jpackage-msi-probe'
$msiProbeResourceRoot = Join-Path $workRoot 'msi-probe-resources'
$msiResourceRoot = Join-Path $workRoot 'msi-resources'
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

function Get-JavaMajorVersion {
    param([Parameter(Mandatory)][string]$JavaPath)

    $versionProperties = @(
        & $JavaPath -XshowSettings:properties -version 2>&1 |
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
    if (-not $versionMatch.Success) {
        throw 'Javaのメジャーバージョンを判定できませんでした'
    }
    return [int]$versionMatch.Groups['major'].Value
}

function Copy-JPackageMsiResources {
    param(
        [Parameter(Mandatory)]
        [string]$Destination,
        [Parameter(Mandatory)]
        [int]$JavaMajorVersion
    )

    $sourceRoot = Join-Path $PSScriptRoot 'resources'
    $mainTemplateName = if ($JavaMajorVersion -ge 25) {
        'main-jdk25.wxs'
    } else {
        'main.wxs'
    }
    $mainTemplate = Join-Path $sourceRoot $mainTemplateName
    if (-not (Test-Path -LiteralPath $mainTemplate -PathType Leaf)) {
        throw "jpackage用WiXテンプレートが見つかりません: $mainTemplate"
    }

    New-Item -ItemType Directory -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $sourceRoot -File |
        Where-Object { $_.Name -notin @('main.wxs', 'main-jdk25.wxs') } |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $Destination
        }
    Copy-Item -LiteralPath $mainTemplate -Destination (
        Join-Path $Destination 'main.wxs'
    )
    Write-Output (
        "JDK $JavaMajorVersion 用WiXテンプレートを使用します: " +
        $mainTemplateName
    )
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

function Copy-DevelopmentPayload {
    $developmentRoot = Join-Path $inputRoot 'development'
    $excludedPrefixes = @(
        '.github/',
        '.vscode/',
        '.settings/',
        '.externalToolBuilders/',
        'cache/',
        'certs/',
        'cvcache/',
        'data/',
        'defaults/',
        'extensions/',
        'lib/',
        'local/',
        'nlFilters/',
        'thcache/',
        'documents/archive/'
    )
    $excludedFiles = @(
        'NicoCache_nl.jar',
        'NicoCache_nl.jar.old',
        'NicoCache_nl.jar.sig',
        'NicoCacheCA.jar',
        'NicoCacheCA.jar.sig',
        'NicoCacheLauncher.jar',
        'NicoCacheBuild.jar'
    )

    $trackedPaths = @(& git -C $root ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw '開発用ペイロードのファイル一覧を取得できませんでした'
    }
    foreach ($relativePath in $trackedPaths) {
        $normalizedPath = $relativePath -replace '\\', '/'
        if ($excludedFiles -contains $normalizedPath -or
                @($excludedPrefixes | Where-Object {
                    $normalizedPath.StartsWith($_, [StringComparison]::OrdinalIgnoreCase)
                }).Count -gt 0) {
            continue
        }
        $source = Join-Path $root ($normalizedPath -replace '/', '\')
        $sourceItem = Get-Item -LiteralPath $source -Force -ErrorAction SilentlyContinue
        if (-not $sourceItem -or $sourceItem.LinkType -or $sourceItem.PSIsContainer) {
            continue
        }
        $destination = Join-Path $developmentRoot ($normalizedPath -replace '/', '\')
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) `
            -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    foreach ($relativePath in @(
            'nlFilters/how-to-update.md',
            'nlFilters/tools/nlfilter-lab/README.md'
        )) {
        $source = Join-Path $root ($relativePath -replace '/', '\')
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "開発用nlFilter文書が見つかりません: $source"
        }
        $destination = Join-Path $developmentRoot ($relativePath -replace '/', '\')
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) `
            -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
}

function Remove-JPackageInstallRootCleaner {
    param(
        [Parameter(Mandatory)]
        [string]$BundlePath
    )

    $document = [Xml]::new()
    $document.PreserveWhitespace = $true
    $document.Load($BundlePath)
    $namespaces = [Xml.XmlNamespaceManager]::new($document.NameTable)
    $namespaces.AddNamespace(
        'wix',
        'http://schemas.microsoft.com/wix/2006/wi'
    )
    $namespaces.AddNamespace(
        'util',
        'http://schemas.microsoft.com/wix/UtilExtension'
    )

    $cleaners = @($document.SelectNodes(
        '//util:RemoveFolderEx[@On="uninstall"]',
        $namespaces
    ))
    if ($cleaners.Count -ne 1) {
        throw (
            'jpackageの導入先再帰削除定義が1件ではありません: ' +
            $cleaners.Count
        )
    }

    $component = $cleaners[0].ParentNode
    if ($component.LocalName -ne 'Component') {
        throw 'jpackageの導入先再帰削除定義がComponent配下にありません'
    }
    $componentId = $component.GetAttribute('Id')
    $propertyId = $cleaners[0].GetAttribute('Property')
    if ([string]::IsNullOrWhiteSpace($componentId) -or
            [string]::IsNullOrWhiteSpace($propertyId)) {
        throw 'jpackageの導入先再帰削除定義に必要なIDがありません'
    }

    $componentRefs = @($document.SelectNodes(
        "//wix:ComponentRef[@Id='$componentId']",
        $namespaces
    ))
    if ($componentRefs.Count -ne 1) {
        throw (
            '導入先再帰削除ComponentRefが1件ではありません: ' +
            $componentRefs.Count
        )
    }
    $properties = @($document.SelectNodes(
        "//wix:Property[@Id='$propertyId']",
        $namespaces
    ))
    if ($properties.Count -ne 1) {
        throw (
            '導入先再帰削除Propertyが1件ではありません: ' +
            $properties.Count
        )
    }

    $component.ParentNode.RemoveChild($component) | Out-Null
    $componentRefs[0].ParentNode.RemoveChild($componentRefs[0]) | Out-Null
    $properties[0].ParentNode.RemoveChild($properties[0]) | Out-Null
    $document.Save($BundlePath)
}

Assert-ChildPath -Path $workRoot -Parent $testRoot
if (Test-Path -LiteralPath $workRoot) {
    $resolvedWorkRoot = (Resolve-Path -LiteralPath $workRoot).Path
    Assert-ChildPath -Path $resolvedWorkRoot -Parent $testRoot
    Remove-Item -LiteralPath $resolvedWorkRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $buildRoot, $dependencyRoot, $inputRoot,
    $outputRoot | Out-Null

$java = Get-RequiredCommand -Name 'java'
$jpackage = Get-RequiredCommand -Name 'jpackage'
$jlink = Get-RequiredCommand -Name 'jlink'
$javaMajorVersion = Get-JavaMajorVersion -JavaPath $java
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

$javaBuildScript = Join-Path $root 'build-javac.ps1'
if (-not (Test-Path -LiteralPath $javaBuildScript -PathType Leaf)) {
    throw "Javaビルドアプリのブートストラップが見つかりません: $javaBuildScript"
}
& $javaBuildScript -JavaVersion $javaMajorVersion `
    -LibraryDirectory $dependencyRoot -Clean
if ($LASTEXITCODE -ne 0) {
    throw 'NicoCacheBuildによる本体・補助アプリのビルドに失敗しました'
}
foreach ($artifactName in @(
        'NicoCache_nl.jar', 'NicoCacheCA.jar', 'NicoCacheLauncher.jar')) {
    $artifactPath = Join-Path $root $artifactName
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
        throw "Javaビルド成果物がありません: $artifactPath"
    }
    Copy-Item -LiteralPath $artifactPath -Destination $inputRoot -Force
}

$distributionFiles = @(
    'certificate-targets.txt',
    'config.properties.default',
    'NicoCacheGUI_native.dll',
    'NicoCacheGUI_native64.dll',
    'niconico-0.ico',
    'nlFilter_sys.txt',
    'proxy_sample.pac',
    'README.md',
    'CHANGELOG.md',
    'how-to-update.md',
    'documents/api.md',
    'documents/tls.md',
    'documents/user-data-root.md',
    'packaging/windows/README.md',
    'packaging/unix/README.md',
    'tests/README.md',
    'nlFilters/how-to-update.md',
    'nlFilters/tools/nlfilter-lab/README.md'
)
foreach ($relativePath in $distributionFiles) {
    Copy-DistributionFile -RelativePath $relativePath
}

$distributionDirectories = @('defaults')
foreach ($relativePath in $distributionDirectories) {
    Copy-DistributionDirectory -RelativePath $relativePath
}
$systemFilesManifest = Join-Path $root 'packaging\system-files.txt'
$systemFiles = @(
    Get-Content -LiteralPath $systemFilesManifest |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') }
)
if ($systemFiles.Count -eq 0) {
    throw "システム資材一覧が空です: $systemFilesManifest"
}
foreach ($relativePath in $systemFiles) {
    Copy-DistributionFile -RelativePath $relativePath
}
Copy-DevelopmentPayload
$cmafPackageScript = Join-Path $root 'tools\cmaf-to-mp4\prepare-package.ps1'
if (-not (Test-Path -LiteralPath $cmafPackageScript -PathType Leaf)) {
    throw "CMAF/Domand変換アプリのパッケージ準備スクリプトが見つかりません: $cmafPackageScript"
}
& $cmafPackageScript -DestinationRoot $inputRoot
if ($LASTEXITCODE -ne 0) {
    throw "CMAF/Domand変換アプリをWindowsパッケージへ追加できません (ExitCode: $LASTEXITCODE)"
}
$writableDirectories = @(
    'data', 'list'
)
foreach ($relativePath in $writableDirectories) {
    New-Item -ItemType Directory -Path (Join-Path $inputRoot $relativePath) `
        -Force | Out-Null
}
foreach ($relativePath in @('data\readme.txt',
        'data\cors\99_sample.conf', 'data\tlsclient\cacerts2')) {
    Copy-DistributionFile -RelativePath $relativePath
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
$moduleResolution = & $java --show-module-resolution -version 2>&1
if ($LASTEXITCODE -ne 0) {
    throw '既定Javaモジュールを解決できませんでした'
}
$runtimeModules = @(
    $moduleResolution | ForEach-Object {
        if ($_ -match '^root (?<module>[A-Za-z0-9.]+) ') {
            $Matches.module
        }
    }
    'jdk.charsets'
    'java.desktop'
    'java.net.http'
) | Sort-Object -Unique
if ($runtimeModules.Count -le 1) {
    throw '既定Javaモジュールの一覧が空です'
}
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
$runtimeImage = Join-Path $buildRoot 'runtime-image'
# JDK 25のjpackage自動ランタイムはネイティブコマンドを省略するため、
# 起動管理アプリが本体JARを子プロセス起動できるjlinkイメージを明示する。
Invoke-NativeCommand -FilePath $jlink -ArgumentList @(
    '--module-path', (Join-Path $javaHome 'jmods'),
    '--add-modules', ($runtimeModules -join ','),
    '--strip-debug', '--no-header-files', '--no-man-pages',
    '--output', $runtimeImage
) -FailureMessage 'Java実行環境の作成に失敗しました'
# 非modular Extension向けの既定集合にEUC-JP文字セットを加える。
$jpackageArguments = @(
    '--type', 'app-image',
    '--runtime-image', $runtimeImage,
    '--name', 'NicoCache_nl',
    '--app-version', $AppVersion,
    '--vendor', 'NicoCache_nl',
    '--description', 'ニコニコ動画向けローカルプロキシー兼キャッシュサーバー',
    '--input', $inputRoot,
    '--dest', $outputRoot,
    '--main-jar', 'NicoCacheLauncher.jar',
    '--main-class', 'nicocache.launcher.LauncherMain',
    '--icon', $launcherIcon
)
foreach ($javaOption in $sharedJavaOptions) {
    $jpackageArguments += @('--java-options', $javaOption)
}
Invoke-NativeCommand -FilePath $jpackage -ArgumentList $jpackageArguments `
    -FailureMessage 'Windowsアプリイメージの作成に失敗しました'

$runtimeLayoutPaths = @(
    'nlFilters'
    $distributionFiles
    $distributionDirectories
    $writableDirectories
    'extensions'
    'local'
    'setup'
    'tools'
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
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) `
        -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
}

if ($PackageType -in @('Msi', 'All')) {
    $msiDescription = 'Local HTTP/HTTPS proxy and cache server for NicoNico'
    $sharedMsiArguments = @(
        '-J-Duser.language=ja',
        '-J-Duser.country=JP',
        '--type', 'msi',
        '--name', 'NicoCache_nl',
        '--app-version', $AppVersion,
        '--vendor', 'NicoCache_nl',
        '--description', $msiDescription,
        '--app-image', $appImagePath,
        '--verbose',
        '--win-per-user-install',
        '--win-menu',
        '--win-menu-group', $packageIdentity.MenuGroup,
        '--win-shortcut',
        '--win-shortcut-prompt',
        '--win-upgrade-uuid', $upgradeUuid.ToString(),
        '--win-dir-chooser'
    )

    Copy-JPackageMsiResources -Destination $msiProbeResourceRoot `
        -JavaMajorVersion $javaMajorVersion
    New-Item -ItemType Directory -Path $msiProbeOutputRoot | Out-Null
    $probeMsiArguments = $sharedMsiArguments + @(
        '--dest', $msiProbeOutputRoot,
        '--temp', $msiProbeTempRoot,
        '--resource-dir', $msiProbeResourceRoot
    )
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList $probeMsiArguments `
        -FailureMessage 'Windows MSIの生成定義作成に失敗しました'

    New-Item -ItemType Directory -Path $msiResourceRoot | Out-Null
    Get-ChildItem -LiteralPath $msiProbeResourceRoot -File |
        Copy-Item -Destination $msiResourceRoot
    $generatedBundle = Join-Path $msiProbeTempRoot 'config\bundle.wxf'
    if (-not (Test-Path -LiteralPath $generatedBundle -PathType Leaf)) {
        throw "jpackageの生成定義が見つかりません: $generatedBundle"
    }
    $customBundle = Join-Path $msiResourceRoot 'bundle.wxf'
    Copy-Item -LiteralPath $generatedBundle -Destination $customBundle
    Remove-JPackageInstallRootCleaner -BundlePath $customBundle

    $finalMsiArguments = $sharedMsiArguments + @(
        '--dest', $outputRoot,
        '--temp', $msiTempRoot,
        '--resource-dir', $msiResourceRoot
    )
    Invoke-NativeCommand -FilePath $jpackage -ArgumentList $finalMsiArguments `
        -FailureMessage 'Windows MSIの作成に失敗しました'
}

if ($PackageType -in @('Zip', 'All')) {
    $archiveName = if ([string]::IsNullOrWhiteSpace($ZipFileName)) {
        "NicoCache_nl-$AppVersion.zip"
    } else {
        $ZipFileName
    }
    if ([System.IO.Path]::GetFileName($archiveName) -ne $archiveName -or
            [System.IO.Path]::GetExtension($archiveName) -ne '.zip') {
        throw "ZIPファイル名が不正です: $archiveName"
    }
    $archivePath = Join-Path $outputRoot $archiveName
    $archiveInputs = @(Get-ChildItem -LiteralPath $appImagePath -Force |
        Select-Object -ExpandProperty FullName)
    if ($archiveInputs.Count -eq 0) {
        throw 'ZIP対象のアプリイメージが空です'
    }
    Compress-Archive -LiteralPath $archiveInputs -DestinationPath $archivePath `
        -CompressionLevel Optimal -Force
    Write-Output "ZIPを作成しました: $archivePath"
}

$artifacts = @(Get-ChildItem -LiteralPath $outputRoot -Force |
    Select-Object Name, FullName, Length)
$artifacts | Format-Table -AutoSize
Write-Output "Windowsパッケージを作成しました: $outputRoot"
