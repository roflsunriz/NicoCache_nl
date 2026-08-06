#Requires -Version 7.0
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
. (Join-Path $root 'java-tool-selection.ps1')
$testRoot = Join-Path $root '.test-work'
$workRoot = Join-Path $testRoot 'windows-package'
$buildRoot = Join-Path $workRoot 'build'
$dependencyRoot = Join-Path $workRoot 'dependencies'
$artifactRoot = Join-Path $buildRoot 'artifacts'
$outputRoot = Join-Path $workRoot 'output'
$appImagePath = Join-Path $outputRoot 'NicoCache_nl'
$runtimeImage = Join-Path $buildRoot 'jre'
$packageIdentity = Import-PowerShellDataFile -LiteralPath (
    Join-Path $PSScriptRoot 'package-identity.psd1'
)

function Assert-ChildPath {
    param([string]$Path, [string]$Parent)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    if (-not $fullPath.StartsWith($fullParent + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でない作業パスです: $fullPath"
    }
}

function Get-RequiredCommand {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "必要なコマンドが見つかりません: $Name" }
    return $command.Source
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
    $modules = @($modules | Sort-Object -Unique)
    if ($modules.Count -le 1) { throw 'JREへ含めるJavaモジュールが空です' }
    return $modules -join ','
}

function New-WindowsMsi {
    param([string]$SourceRoot, [string]$Destination)
    $heat = Get-RequiredCommand 'heat'
    $candle = Get-RequiredCommand 'candle'
    $light = Get-RequiredCommand 'light'
    $harvest = Join-Path $buildRoot 'application-files.wxs'
    $product = Join-Path $buildRoot 'product.wxs'
    $objectRoot = Join-Path $buildRoot 'wix-obj'
    New-Item -ItemType Directory -Path $objectRoot -Force | Out-Null
    Invoke-NativeCommand $heat @(
        'dir', $SourceRoot, '-nologo', '-cg', 'ApplicationFiles',
        '-dr', 'INSTALLFOLDER', '-scom', '-sreg', '-sfrag', '-srd',
        '-ag', '-var', 'var.SourceRoot', '-out', $harvest
    ) 'アプリケーションルートのWiX定義生成に失敗しました'

    # heatはファイルを含むディレクトリの削除行を生成しないため、
    # アンインストール後に空のアプリ階層を残さないRemoveFolderを補う。
    $harvestXml = [Xml]::new()
    $harvestXml.PreserveWhitespace = $true
    $harvestXml.Load($harvest)
    $namespace = 'http://schemas.microsoft.com/wix/2006/wi'
    $manager = [Xml.XmlNamespaceManager]::new($harvestXml.NameTable)
    $manager.AddNamespace('wix', $namespace)
    $ownerComponent = $harvestXml.SelectSingleNode('//wix:Component', $manager)
    $directoryIds = @('INSTALLFOLDER') + @(
        $harvestXml.SelectNodes('//wix:Directory', $manager) |
            ForEach-Object { $_.GetAttribute('Id') }
    )
    $removeIndex = 0
    foreach ($directoryId in @($directoryIds | Sort-Object -Unique)) {
        $remove = $harvestXml.CreateElement('RemoveFolder', $namespace)
        $remove.SetAttribute('Id', "RemoveApplicationDirectory$removeIndex")
        $remove.SetAttribute('Directory', $directoryId)
        $remove.SetAttribute('On', 'uninstall')
        [void]$ownerComponent.AppendChild($remove)
        $removeIndex++
    }
    $harvestXml.Save($harvest)

    $parts = @($AppVersion.Split('.'))
    while ($parts.Count -lt 3) { $parts += '0' }
    $msiVersion = $parts[0..2] -join '.'
    $upgradeCode = [Security.SecurityElement]::Escape([string]$packageIdentity.UpgradeUuid)
    $icon = [Security.SecurityElement]::Escape((Resolve-Path -LiteralPath (
        Join-Path $PSScriptRoot 'assets\nicocache-launcher.ico')).Path)
    $xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*" Name="NicoCache_nl" Language="1041" Codepage="932" Version="$msiVersion" Manufacturer="NicoCache_nl" UpgradeCode="$upgradeCode">
    <Package InstallerVersion="500" Compressed="yes" InstallScope="perUser" Platform="x64" SummaryCodepage="932" Description="ニコニコ動画向けローカルプロキシー兼キャッシュサーバー" />
    <MajorUpgrade DowngradeErrorMessage="新しいバージョンが既にインストールされています。" />
    <MediaTemplate EmbedCab="yes" />
    <Property Id="ARPPRODUCTICON" Value="LauncherIcon" />
    <Property Id="WIXUI_INSTALLDIR" Value="INSTALLFOLDER" />
    <Property Id="MSIINSTALLPERUSER" Value="1" />
    <Property Id="NICOCACHE_INSTALLDIR">
      <RegistrySearch Id="NicoCacheInstallDirSearch" Root="HKCU" Key="Software\NicoCache_nl\Installer" Name="InstallDir" Type="directory" Win64="yes" />
    </Property>
    <Icon Id="LauncherIcon" SourceFile="$icon" />
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="LocalAppDataFolder"><Directory Id="ProgramsFolder" Name="Programs"><Directory Id="INSTALLFOLDER" Name="NicoCache_nl" /></Directory></Directory>
      <Directory Id="ProgramMenuFolder"><Directory Id="ApplicationProgramsFolder" Name="NicoCache_nl" /></Directory>
      <Directory Id="DesktopFolder" />
    </Directory>
    <DirectoryRef Id="INSTALLFOLDER">
      <Component Id="NicoCacheInstallState" Guid="{3DAF0BA2-122D-4E0A-91CC-D2CC80B11C5B}" Win64="yes">
        <RegistryKey Root="HKCU" Key="Software\NicoCache_nl\Installer">
          <RegistryValue Name="InstallDir" Type="string" Value="[INSTALLFOLDER]" KeyPath="yes" />
        </RegistryKey>
      </Component>
      <Component Id="LauncherShortcuts" Guid="*">
        <Shortcut Id="ApplicationStartMenuShortcut" Directory="ApplicationProgramsFolder" Name="NicoCache_nl" WorkingDirectory="INSTALLFOLDER" Target="[INSTALLFOLDER]jre\bin\javaw.exe" Arguments="-jar &quot;[INSTALLFOLDER]NicoCacheLauncher.jar&quot;" Icon="LauncherIcon" />
        <Shortcut Id="ApplicationDesktopShortcut" Directory="DesktopFolder" Name="NicoCache_nl" WorkingDirectory="INSTALLFOLDER" Target="[INSTALLFOLDER]jre\bin\javaw.exe" Arguments="-jar &quot;[INSTALLFOLDER]NicoCacheLauncher.jar&quot;" Icon="LauncherIcon" />
        <RemoveFolder Id="RemoveApplicationProgramsFolder" Directory="ApplicationProgramsFolder" On="uninstall" />
        <RegistryValue Root="HKCU" Key="Software\NicoCache_nl" Name="installed" Type="integer" Value="1" KeyPath="yes" />
      </Component>
    </DirectoryRef>
    <Feature Id="ProductFeature" Title="NicoCache_nl" Level="1">
      <ComponentGroupRef Id="ApplicationFiles" />
      <ComponentRef Id="NicoCacheInstallState" />
      <ComponentRef Id="LauncherShortcuts" />
    </Feature>
    <CustomAction Id="NicoCacheRestoreInstallDir" Property="INSTALLFOLDER" Value="[NICOCACHE_INSTALLDIR]" Execute="firstSequence" />
    <CustomAction Id="NicoCacheSetArpInstallLocation" Property="ARPINSTALLLOCATION" Value="[INSTALLFOLDER]" />
    <CustomAction Id="NicoCacheRollbackWindowsSetup" Directory="TARGETDIR" ExeCommand="&quot;[SystemFolder]WindowsPowerShell\v1.0\powershell.exe&quot; -WindowStyle Hidden -NoProfile -NonInteractive -ExecutionPolicy Bypass -File &quot;[NICOCACHE_INSTALLDIR]setup\windows\first-run-setup.ps1&quot; -Action Rollback -RemoveApplicationConfig" Execute="immediate" Return="check" />
    <InstallExecuteSequence>
      <Custom Action="NicoCacheRestoreInstallDir" After="AppSearch">NICOCACHE_INSTALLDIR</Custom>
      <Custom Action="NicoCacheSetArpInstallLocation" After="CostFinalize">NOT Installed</Custom>
      <Custom Action="NicoCacheRollbackWindowsSetup" Before="RemoveFiles">REMOVE="ALL" AND NOT UPGRADINGPRODUCTCODE AND NICOCACHE_INSTALLDIR</Custom>
    </InstallExecuteSequence>
    <UIRef Id="WixUI_InstallDir" />
  </Product>
</Wix>
"@
    [IO.File]::WriteAllText($product, $xml, [Text.UTF8Encoding]::new($false))
    Invoke-NativeCommand $candle @(
        '-nologo', '-ext', 'WixUIExtension', "-dSourceRoot=$SourceRoot",
        '-out', ($objectRoot + '\'), $product, $harvest
    ) 'WiXソースのコンパイルに失敗しました'
    Invoke-NativeCommand $light @(
        '-nologo', '-ext', 'WixUIExtension', '-cultures:ja-jp', '-spdb',
        # このMSIはInstallScope=perUser固定であり、ユーザーをまたぐ
        # advertised componentを持たない。ファイルをKeyPathにして修復を
        # 成立させるため、per-user profile向けの一般検査だけを除外する。
        '-sice:ICE38',
        # INSTALLFOLDER配下には上でRemoveFolderを付与済み。共有親の
        # LocalAppData\Programsだけは削除対象にできないため除外する。
        '-sice:ICE64',
        # InstallScope=perUser固定なのでper-machine切替時だけ問題になる
        # ICE91は該当しない。
        '-sice:ICE91',
        '-out', $Destination, (Join-Path $objectRoot 'product.wixobj'),
        (Join-Path $objectRoot 'application-files.wixobj')
    ) 'Windows MSIの作成に失敗しました'
}

Assert-ChildPath $workRoot $testRoot
if (Test-Path -LiteralPath $workRoot) {
    $resolved = (Resolve-Path -LiteralPath $workRoot).Path
    Assert-ChildPath $resolved $testRoot
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Path $buildRoot, $dependencyRoot, $outputRoot | Out-Null
$java = Get-RequiredCommand 'java'
$jlink = Get-RequiredCommand 'jlink'
$javaMajor = (Assert-TemurinJavaRuntime -JavaPath $java -JavaVersion 25).Major
& (Join-Path $PSScriptRoot 'prepare-dependencies.ps1') -DestinationDirectory $dependencyRoot
if ($LASTEXITCODE -ne 0) { throw '依存JARの準備に失敗しました' }
& (Join-Path $root 'build-javac.ps1') -JavaVersion $javaMajor `
    -LibraryDirectory $dependencyRoot -OutputDirectory $artifactRoot -Clean
if ($LASTEXITCODE -ne 0) { throw '配布JARのビルドに失敗しました' }

$javaHome = Split-Path -Parent (Split-Path -Parent $java)
Invoke-NativeCommand $jlink @(
    '--module-path', (Join-Path $javaHome 'jmods'),
    '--add-modules', (Get-RuntimeModules $java), '--strip-debug',
    '--no-header-files', '--no-man-pages', '--output', $runtimeImage
) '同梱JREの作成に失敗しました'
& (Join-Path $root 'packaging\prepare-application-root.ps1') `
    -DestinationRoot $appImagePath -RuntimeImage $runtimeImage `
    -DependencyDirectory $dependencyRoot -ArtifactDirectory $artifactRoot `
    -AppVersion $AppVersion -Platform Windows
if ($LASTEXITCODE -ne 0) { throw 'Windowsアプリケーションルートの作成に失敗しました' }

if ($PackageType -in @('Msi', 'All')) {
    New-WindowsMsi $appImagePath (Join-Path $outputRoot "NicoCache_nl-$AppVersion.msi")
}
if ($PackageType -in @('Zip', 'All')) {
    $archiveName = if ([string]::IsNullOrWhiteSpace($ZipFileName)) {
        "NicoCache_nl-$AppVersion.zip"
    } else { $ZipFileName }
    if ([IO.Path]::GetFileName($archiveName) -ne $archiveName -or
            [IO.Path]::GetExtension($archiveName) -ne '.zip') {
        throw "ZIPファイル名が不正です: $archiveName"
    }
    $inputs = @(Get-ChildItem -LiteralPath $appImagePath -Force |
        Select-Object -ExpandProperty FullName)
    Compress-Archive -LiteralPath $inputs -DestinationPath (
        Join-Path $outputRoot $archiveName) -CompressionLevel Optimal -Force
}
Get-ChildItem -LiteralPath $outputRoot -Force |
    Select-Object Name, FullName, Length | Format-Table -AutoSize
Write-Output "Windowsパッケージを作成しました: $outputRoot"
