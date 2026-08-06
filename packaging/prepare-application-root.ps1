#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$DestinationRoot,

    [Parameter(Mandatory)]
    [string]$RuntimeImage,

    [Parameter(Mandatory)]
    [string]$DependencyDirectory,

    [string]$ArtifactDirectory,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$AppVersion,

    [Parameter(Mandatory)]
    [ValidateSet('Windows', 'Linux', 'MacOS')]
    [string]$Platform
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$destination = [System.IO.Path]::GetFullPath($DestinationRoot)
$runtime = (Resolve-Path -LiteralPath $RuntimeImage).Path
$dependencies = (Resolve-Path -LiteralPath $DependencyDirectory).Path
$artifacts = if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) {
    $root
} else {
    (Resolve-Path -LiteralPath $ArtifactDirectory).Path
}

if (Test-Path -LiteralPath $destination) {
    if (@(Get-ChildItem -LiteralPath $destination -Force).Count -ne 0) {
        throw "アプリケーションルートが空ではありません: $destination"
    }
} else {
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
}

# 配布物のアプリケーションルートは、特殊な再配置を行わず、Gitで管理する
# checkoutの相対配置をそのまま複製する。JAR、依存JAR、JREだけを後から重ねる。
$trackedPaths = @(& git -C $root -c core.quotePath=false ls-files --cached)
if ($LASTEXITCODE -ne 0 -or $trackedPaths.Count -eq 0) {
    throw 'Git管理対象のアプリケーションファイル一覧を取得できませんでした'
}
$deletedPaths = @(& git -C $root -c core.quotePath=false ls-files --deleted)
if ($LASTEXITCODE -ne 0) {
    throw '作業ツリーで削除されたGit管理対象ファイルを確認できませんでした'
}
$deleted = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::Ordinal
)
foreach ($deletedPath in $deletedPaths) { [void]$deleted.Add($deletedPath) }
foreach ($relativePath in $trackedPaths) {
    if ([string]::IsNullOrWhiteSpace($relativePath)) { continue }
    if ($deleted.Contains($relativePath)) { continue }
    $source = Join-Path $root ($relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    $sourceItem = Get-Item -LiteralPath $source -Force -ErrorAction SilentlyContinue
    if (-not $sourceItem) {
        throw "Git管理対象ファイルがcheckoutにありません: $relativePath"
    }
    if ($sourceItem.LinkType) {
        throw "配布物へシンボリックリンクを複製できません: $relativePath"
    }
    if ($sourceItem.PSIsContainer) { continue }
    $target = Join-Path $destination ($relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force |
        Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
}

foreach ($artifactName in @(
        'NicoCache_nl.jar',
        'NicoCacheCA.jar',
        'NicoCacheLauncher.jar',
        'NicoCacheBuild.jar'
    )) {
    $source = Join-Path $artifacts $artifactName
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "プリコンパイル済みJARがありません: $source"
    }
    Copy-Item -LiteralPath $source -Destination (
        Join-Path $destination $artifactName
    ) -Force
}

$lib = Join-Path $destination 'lib'
New-Item -ItemType Directory -Path $lib -Force | Out-Null
foreach ($dependency in Get-ChildItem -LiteralPath $dependencies -File) {
    Copy-Item -LiteralPath $dependency.FullName -Destination (
        Join-Path $lib $dependency.Name
    ) -Force
}

$cmafPackageScript = Join-Path $root 'tools/cmaf-to-mp4/prepare-package.ps1'
& $cmafPackageScript -DestinationRoot $destination
if ($LASTEXITCODE -ne 0) {
    throw "CMAF/Domand変換アプリを配布ルートへ追加できません (ExitCode: $LASTEXITCODE)"
}

Copy-Item -LiteralPath (
    Join-Path $root 'packaging/windows/THIRD-PARTY-NOTICES.txt'
) -Destination (Join-Path $destination 'THIRD-PARTY-NOTICES.txt') -Force

$jreDestination = Join-Path $destination 'jre'
Copy-Item -LiteralPath $runtime -Destination $jreDestination -Recurse -Force
Set-Content -LiteralPath (Join-Path $destination 'NicoCache_nl.version') `
    -Value $AppVersion -Encoding ascii -NoNewline

if ($Platform -eq 'Windows') {
    $setupTarget = Join-Path $destination 'setup/windows/first-run-setup.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent $setupTarget) -Force |
        Out-Null
    $setupContent = [IO.File]::ReadAllText(
        (Join-Path $root 'packaging/windows/runtime/first-run-setup.ps1'),
        [Text.UTF8Encoding]::new($false, $true)
    )
    [IO.File]::WriteAllText(
        $setupTarget, $setupContent, [Text.UTF8Encoding]::new($true)
    )
    $launcher = @'
@echo off
setlocal
start "" "%~dp0jre\bin\javaw.exe" -jar "%~dp0NicoCacheLauncher.jar" %*
'@
    [IO.File]::WriteAllText(
        (Join-Path $destination 'NicoCache_nl.cmd'),
        $launcher.Replace("`n", "`r`n"),
        [Text.UTF8Encoding]::new($false)
    )
} else {
    $launcher = @'
#!/bin/sh
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$APP_ROOT/jre/bin/java" -jar "$APP_ROOT/NicoCacheLauncher.jar" "$@"
'@
    $launcherPath = Join-Path $destination 'NicoCache_nl'
    [IO.File]::WriteAllText(
        $launcherPath,
        $launcher.Replace("`r`n", "`n"),
        [Text.UTF8Encoding]::new($false)
    )
    & chmod +x $launcherPath
    if ($LASTEXITCODE -ne 0) {
        throw "Unixランチャーへ実行権限を設定できません: $launcherPath"
    }
}

Write-Output "clone相当のアプリケーションルートを作成しました: $destination"
