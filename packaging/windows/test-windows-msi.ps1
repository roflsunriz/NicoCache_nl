[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$MsiPath,

    [Parameter(Mandatory)]
    [string]$PreviousMsiPath,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$ExpectedPreviousVersion,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$ExpectedCurrentVersion,

    [ValidateRange(5, 120)]
    [int]$StartupTimeoutSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:GITHUB_ACTIONS -ne 'true') {
    throw 'MSI試験は製品登録を伴うため、一時GitHub Actionsランナー以外では実行できません'
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $root '.test-work')).
    TrimEnd([System.IO.Path]::DirectorySeparatorChar)
$installRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $testRoot 'windows-msi-install')
)
$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
$resolvedPreviousMsi = (Resolve-Path -LiteralPath $PreviousMsiPath).Path
$menuGroup = (Import-PowerShellDataFile -LiteralPath (
        Join-Path $PSScriptRoot 'package-identity.psd1'
    )).MenuGroup
$startMenuShortcut = Join-Path $env:APPDATA (
    "Microsoft\Windows\Start Menu\Programs\$menuGroup\NicoCache_nl.lnk"
)
$desktopShortcut = Join-Path (
    [Environment]::GetFolderPath(
        [Environment+SpecialFolder]::DesktopDirectory
    )
) 'NicoCache_nl.lnk'
$runRegistryPath =
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValueName = 'NicoCache_nl'

if (-not $installRoot.StartsWith(
        $testRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "安全でないMSIテスト先です: $installRoot"
}
if (Test-Path -LiteralPath $installRoot) {
    throw "MSIテスト先が既に存在します: $installRoot"
}

function Invoke-MsiExec {
    param(
        [Parameter(Mandatory)]
        [string[]]$ArgumentList,
        [Parameter(Mandatory)]
        [string]$FailureMessage
    )

    $process = Start-Process -FilePath 'msiexec.exe' `
        -ArgumentList $ArgumentList `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "$FailureMessage (ExitCode: $($process.ExitCode))"
    }
}

function Get-OsIntegrationState {
    $proxyPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $proxy = Get-ItemProperty -LiteralPath $proxyPath -ErrorAction SilentlyContinue
    $proxyState = [ordered]@{}
    foreach ($name in @('AutoConfigURL', 'AutoDetect', 'ProxyEnable',
            'ProxyServer', 'ProxyOverride')) {
        $property = if ($proxy) { $proxy.PSObject.Properties[$name] } else { $null }
        $proxyState[$name] = if ($property) { $property.Value } else { $null }
    }
    $certificateThumbprints = @(
        Get-ChildItem -LiteralPath 'Cert:\CurrentUser\Root' |
            Select-Object -ExpandProperty Thumbprint |
            Sort-Object
    )
    $uninstallEntries = @(
        Get-ChildItem -LiteralPath (
            'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
        ) -ErrorAction SilentlyContinue |
            ForEach-Object {
                Get-ItemProperty -LiteralPath $_.PSPath `
                    -ErrorAction SilentlyContinue
            } |
            Where-Object { $_.DisplayName -eq 'NicoCache_nl' } |
            Select-Object DisplayName, DisplayVersion, UninstallString |
            Sort-Object DisplayVersion, UninstallString
    )
    $run = Get-ItemProperty -LiteralPath $runRegistryPath `
        -Name $runValueName `
        -ErrorAction SilentlyContinue
    $runProperty = if ($run) {
        $run.PSObject.Properties[$runValueName]
    } else {
        $null
    }
    return [PSCustomObject]@{
        Proxy = $proxyState
        RunValue = if ($runProperty) { $runProperty.Value } else { $null }
        CertificateThumbprints = $certificateThumbprints
        UninstallEntries = $uninstallEntries
        StartMenuShortcutExists = Test-Path -LiteralPath $startMenuShortcut
        DesktopShortcutExists = Test-Path -LiteralPath $desktopShortcut
    } | ConvertTo-Json -Depth 5 -Compress
}

function Assert-NoInstalledProcess {
    $installedProcesses = @(
        Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                try {
                    $_.Path -and $_.Path.StartsWith(
                        $installRoot + [System.IO.Path]::DirectorySeparatorChar,
                        [System.StringComparison]::OrdinalIgnoreCase
                    )
                } catch {
                    $false
                }
            }
    )
    if ($installedProcesses.Count -gt 0) {
        throw 'MSI処理後にNicoCache_nlが意図せず起動しています'
    }
}

function Assert-AppVersion {
    param([Parameter(Mandatory)][string]$ExpectedVersion)

    $launcherConfig = Join-Path $installRoot 'app\NicoCache_nl.cfg'
    if (-not (Test-Path -LiteralPath $launcherConfig -PathType Leaf)) {
        throw "ランチャー設定がありません: $launcherConfig"
    }
    if ((Get-Content -Raw -LiteralPath $launcherConfig) -notmatch
            [regex]::Escape("-Djpackage.app-version=$ExpectedVersion")) {
        throw "インストール済みバージョンが $ExpectedVersion ではありません"
    }
}

function Get-InstalledProductCode {
    param([Parameter(Mandatory)][string]$ExpectedVersion)

    $entries = @(
        Get-ChildItem -LiteralPath (
            'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
        ) -ErrorAction SilentlyContinue |
            Where-Object {
                $properties = Get-ItemProperty -LiteralPath $_.PSPath `
                    -ErrorAction SilentlyContinue
                $properties.DisplayName -eq 'NicoCache_nl' -and
                    $properties.DisplayVersion -eq $ExpectedVersion
            }
    )
    if ($entries.Count -ne 1) {
        throw (
            "インストール済みMSIの製品コードを一意に特定できません " +
            "(version=$ExpectedVersion, count=$($entries.Count))"
        )
    }
    $productCode = $entries[0].PSChildName
    if ($productCode -notmatch
            '^\{[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}$') {
        throw "MSIの製品コードがGUIDではありません: $productCode"
    }
    return $productCode
}

$osStateBefore = Get-OsIntegrationState
$installed = $false
$upgraded = $false
$userStatePath = Join-Path $installRoot 'data\installer-lifecycle-user.txt'
$setupStatePath = Join-Path $installRoot 'data\setup-system-state.json'
$initialCertificateFiles = @()

try {
    Invoke-MsiExec -ArgumentList @(
        '/i',
        "`"$resolvedPreviousMsi`"",
        '/qn',
        '/norestart',
        "INSTALLDIR=`"$installRoot`""
    ) -FailureMessage '旧版MSIの無人インストールに失敗しました'
    $installed = $true

    if (-not (Test-Path -LiteralPath $installRoot -PathType Container)) {
        throw "MSIが指定先へインストールされませんでした: $installRoot"
    }
    Assert-AppVersion -ExpectedVersion $ExpectedPreviousVersion
    $previousProductCode =
        Get-InstalledProductCode -ExpectedVersion $ExpectedPreviousVersion
    Assert-NoInstalledProcess
    if (-not (Test-Path -LiteralPath $startMenuShortcut -PathType Leaf)) {
        throw "スタートメニューのショートカットがありません: $startMenuShortcut"
    }
    if (-not (Test-Path -LiteralPath $desktopShortcut -PathType Leaf)) {
        throw "デスクトップのショートカットがありません: $desktopShortcut"
    }
    Write-Output 'PASS 旧版MSIの無人インストール・ショートカット・起動抑止'

    New-Item -ItemType Directory -Path (Split-Path -Parent $userStatePath) `
        -Force | Out-Null
    Set-Content -LiteralPath $userStatePath `
        -Value 'preserve-user-state-across-repair-and-upgrade' `
        -Encoding ascii

    $repairTarget = Join-Path $installRoot 'nlFilter_sys.txt'
    if (-not (Test-Path -LiteralPath $repairTarget -PathType Leaf)) {
        throw "修復対象ファイルがありません: $repairTarget"
    }
    Remove-Item -LiteralPath $repairTarget -Force
    Invoke-MsiExec -ArgumentList @(
        '/fa',
        $previousProductCode,
        '/qn',
        '/norestart'
    ) -FailureMessage 'MSI修復に失敗しました'
    if (-not (Test-Path -LiteralPath $repairTarget -PathType Leaf)) {
        throw 'MSI修復で配布ファイルが復元されませんでした'
    }
    if (-not (Test-Path -LiteralPath $desktopShortcut -PathType Leaf)) {
        throw 'MSI修復後にデスクトップのショートカットがありません'
    }
    if ((Get-Content -Raw -LiteralPath $userStatePath).Trim() -ne
            'preserve-user-state-across-repair-and-upgrade') {
        throw 'MSI修復でユーザー状態が変化しました'
    }
    Write-Output 'PASS MSI修復とユーザー状態の保全'

    Invoke-MsiExec -ArgumentList @(
        '/i',
        "`"$resolvedMsi`"",
        '/qn',
        '/norestart',
        "INSTALLDIR=`"$installRoot`""
    ) -FailureMessage '新版MSIへの無人更新に失敗しました'
    $upgraded = $true
    Assert-AppVersion -ExpectedVersion $ExpectedCurrentVersion
    Assert-NoInstalledProcess
    if (-not (Test-Path -LiteralPath $desktopShortcut -PathType Leaf)) {
        throw 'MSI更新後にデスクトップのショートカットがありません'
    }
    if ((Get-Content -Raw -LiteralPath $userStatePath).Trim() -ne
            'preserve-user-state-across-repair-and-upgrade') {
        throw 'MSI更新でユーザー状態が失われました'
    }
    Write-Output 'PASS 旧版から新版への更新とユーザー状態の保全'

    & (Join-Path $PSScriptRoot 'test-windows-app-image.ps1') `
        -AppImagePath $installRoot `
        -StartupTimeoutSeconds $StartupTimeoutSeconds
    Write-Output 'PASS 更新後MSIの隔離起動'

    $certificateDirectory = Join-Path $installRoot 'certs'
    $initialCertificateFiles = @(
        Get-ChildItem -LiteralPath $certificateDirectory -File `
            -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    )
    $launcher = Join-Path $installRoot 'NicoCache_nl.exe'
    $setupProcess = Start-Process `
        -FilePath $launcher `
        -ArgumentList @(
            '--setup',
            '--headless',
            '--https=true',
            '--trust-certificate=false',
            '--proxy=true',
            '--autostart=true'
        ) `
        -WorkingDirectory $installRoot `
        -Wait `
        -PassThru
    if ($setupProcess.ExitCode -ne 0) {
        throw "MSI上の初回セットアップに失敗しました (ExitCode: $($setupProcess.ExitCode))"
    }
    $savedState = Get-Content -Raw -LiteralPath $setupStatePath -Encoding UTF8 |
        ConvertFrom-Json
    if ($savedState.Status -ne 'Applied') {
        throw "Windows連携状態がAppliedではありません: $($savedState.Status)"
    }
    $proxy = Get-ItemProperty -LiteralPath (
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    )
    if ($proxy.AutoConfigURL -ne 'http://localhost:8080/proxy.pac') {
        throw 'MSI上の初回セットアップで自動プロキシーが設定されませんでした'
    }
    $runValue = (Get-ItemProperty -LiteralPath $runRegistryPath `
            -Name $runValueName).$runValueName
    if ($runValue -notmatch [regex]::Escape($launcher)) {
        throw 'MSI上の初回セットアップで自動起動が設定されませんでした'
    }
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        (Join-Path $certificateDirectory 'ca.cer')
    )
    if (Test-Path -LiteralPath (
            "Cert:\CurrentUser\Root\$($certificate.Thumbprint)"
        )) {
        throw 'MSIの非対話試験でCAが信頼済みルートへ登録されました'
    }
    Write-Output 'PASS アンインストール前のWindows連携適用（CA未登録）'
} finally {
    if (Test-Path -LiteralPath $userStatePath) {
        Remove-Item -LiteralPath $userStatePath -Force
    }
    foreach ($generatedPath in @(
            (Join-Path $installRoot 'config.properties'),
            (Join-Path $installRoot 'NicoCacheGUI.property'),
            (Join-Path $installRoot 'data\first-run-setup.properties'),
            (Join-Path $installRoot 'proxy.pac')
        )) {
        if (Test-Path -LiteralPath $generatedPath -PathType Leaf) {
            Remove-Item -LiteralPath $generatedPath -Force
        }
    }
    if (Test-Path -LiteralPath (Join-Path $installRoot 'certs')) {
        Get-ChildItem -LiteralPath (Join-Path $installRoot 'certs') -File |
            Where-Object { $_.FullName -notin $initialCertificateFiles } |
            Remove-Item -Force
    }
    if ($installed) {
        $uninstallMsi = if ($upgraded) { $resolvedMsi } else { $resolvedPreviousMsi }
        Invoke-MsiExec -ArgumentList @(
            '/x',
            "`"$uninstallMsi`"",
            '/qn',
            '/norestart'
        ) -FailureMessage 'MSIの無人アンインストールに失敗しました'
    }
}

Assert-NoInstalledProcess
if (Test-Path -LiteralPath $installRoot) {
    $remainingFiles = @(Get-ChildItem -LiteralPath $installRoot -Recurse -File)
    if ($remainingFiles.Count -gt 0) {
        $names = $remainingFiles | Select-Object -ExpandProperty FullName
        throw "アンインストール後に製品ファイルが残っています:`n$($names -join "`n")"
    }
    Remove-Item -LiteralPath $installRoot -Recurse -Force
}
if (Test-Path -LiteralPath $startMenuShortcut) {
    throw 'アンインストール後にスタートメニューのショートカットが残っています'
}
if (Test-Path -LiteralPath $desktopShortcut) {
    throw 'アンインストール後にデスクトップのショートカットが残っています'
}
$osStateAfter = Get-OsIntegrationState
if ($osStateAfter -ne $osStateBefore) {
    throw 'MSI試験の前後でOS統合状態が変化しました'
}
Write-Output 'PASS MSIの無人アンインストールとOS統合状態の復元'
