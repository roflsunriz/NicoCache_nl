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
$localAppDataRoot = [System.IO.Path]::GetFullPath($env:LOCALAPPDATA).
    TrimEnd([System.IO.Path]::DirectorySeparatorChar)
$installRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $localAppDataRoot 'NicoCache_nl')
)
$legacyInstallRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $localAppDataRoot 'Programs\NicoCache_nl')
)
$customInstallRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $testRoot 'windows-msi-custom-install')
)
$userDataRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $testRoot 'windows-msi-user-data')
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
$stateLocatorRegistryPath = 'HKCU:\Software\NicoCache_nl'
$stateLocatorValueName = 'SetupStatePath'

foreach ($candidate in @($installRoot, $legacyInstallRoot)) {
    if (-not $candidate.StartsWith(
            $localAppDataRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないMSIテスト先です: $candidate"
    }
}
if (-not $customInstallRoot.StartsWith(
        $testRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "安全でないMSIカスタム試験先です: $customInstallRoot"
}
if (Test-Path -LiteralPath $customInstallRoot) {
    throw "MSIカスタム試験先が既に存在します: $customInstallRoot"
}
foreach ($candidate in @($installRoot, $legacyInstallRoot)) {
    if (Test-Path -LiteralPath $candidate) {
        throw "MSIテスト先が既に存在します: $candidate"
    }
}
if (Test-Path -LiteralPath $userDataRoot) {
    throw "MSI利用者データ試験先が既に存在します: $userDataRoot"
}
function Invoke-MsiExec {
    param(
        [Parameter(Mandatory)]
        [string[]]$ArgumentList,
        [Parameter(Mandatory)]
        [string]$FailureMessage,
        [Parameter(Mandatory)]
        [string]$LogPath
    )

    if (Test-Path -LiteralPath $LogPath -PathType Leaf) {
        Remove-Item -LiteralPath $LogPath -Force
    }
    $effectiveArguments = @($ArgumentList) + @(
        '/L*V',
        "`"$LogPath`""
    )
    $process = Start-Process -FilePath 'msiexec.exe' `
        -ArgumentList $effectiveArguments `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        $logDetails = if (Test-Path -LiteralPath $LogPath -PathType Leaf) {
            $importantLines = @(
                Select-String -LiteralPath $LogPath `
                    -Pattern (
                        'Return value 3|NicoCacheRollbackWindowsSetup|' +
                        'CustomAction|Error |Exception|failed|Product:'
                    ) `
                    -ErrorAction SilentlyContinue |
                    Select-Object -Last 120 |
                    ForEach-Object { $_.Line }
            )
            $tailLines = @(
                Get-Content -LiteralPath $LogPath -Tail 20 `
                    -ErrorAction SilentlyContinue
            )
            (@($importantLines) + @($tailLines)) -join "`n"
        } else {
            'MSIログは作成されませんでした'
        }
        throw (
            "$FailureMessage (ExitCode: $($process.ExitCode))`n" +
            "MSIログ: $LogPath`n$logDetails"
        )
    }
}

function Get-MsiProductCode {
    param([Parameter(Mandatory)][string]$Path)

    $installer = $null
    $database = $null
    $view = $null
    $record = $null
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $database = $installer.GetType().InvokeMember(
            'OpenDatabase',
            [Reflection.BindingFlags]::InvokeMethod,
            $null,
            $installer,
            @($Path, 0)
        )
        $view = $database.GetType().InvokeMember(
            'OpenView',
            [Reflection.BindingFlags]::InvokeMethod,
            $null,
            $database,
            @(
                'SELECT `Value` FROM `Property` ' +
                "WHERE `Property` = 'ProductCode'"
            )
        )
        $view.GetType().InvokeMember(
            'Execute',
            [Reflection.BindingFlags]::InvokeMethod,
            $null,
            $view,
            $null
        ) | Out-Null
        $record = $view.GetType().InvokeMember(
            'Fetch',
            [Reflection.BindingFlags]::InvokeMethod,
            $null,
            $view,
            $null
        )
        if (-not $record) {
            throw "MSIにProductCodeがありません: $Path"
        }
        $productCode = $record.StringData(1)
        if ($productCode -notmatch
                '^\{[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}$') {
            throw "MSIのProductCodeがGUIDではありません: $productCode"
        }
        return $productCode
    } finally {
        if ($record) {
            [Runtime.InteropServices.Marshal]::FinalReleaseComObject(
                $record
            ) | Out-Null
        }
        if ($view) {
            $view.GetType().InvokeMember(
                'Close',
                [Reflection.BindingFlags]::InvokeMethod,
                $null,
                $view,
                $null
            ) | Out-Null
            [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) |
                Out-Null
        }
        if ($database) {
            [Runtime.InteropServices.Marshal]::FinalReleaseComObject(
                $database
            ) | Out-Null
        }
        if ($installer) {
            [Runtime.InteropServices.Marshal]::FinalReleaseComObject(
                $installer
            ) | Out-Null
        }
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
        StateLocator = Get-ItemProperty `
            -LiteralPath $stateLocatorRegistryPath `
            -Name $stateLocatorValueName `
            -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty $stateLocatorValueName `
                -ErrorAction SilentlyContinue
    } | ConvertTo-Json -Depth 5 -Compress
}

function Assert-NoInstalledProcess {
    param(
        [string[]]$ApplicationRoots = @($installRoot, $legacyInstallRoot)
    )

    $installedProcesses = @(
        Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                try {
                    $processPath = $_.Path
                    $matchesApplicationRoot = $false
                    foreach ($applicationRoot in $ApplicationRoots) {
                        if ($processPath -and $processPath.StartsWith(
                                $applicationRoot +
                                [System.IO.Path]::DirectorySeparatorChar,
                                [System.StringComparison]::OrdinalIgnoreCase
                            )) {
                            $matchesApplicationRoot = $true
                            break
                        }
                    }
                    $matchesApplicationRoot
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
    param(
        [Parameter(Mandatory)][string]$ExpectedVersion,
        [Parameter(Mandatory)][string]$ApplicationRoot
    )

    $versionFile = Join-Path $ApplicationRoot 'NicoCache_nl.version'
    if (-not (Test-Path -LiteralPath $versionFile -PathType Leaf)) {
        throw "配布版番号ファイルがありません: $versionFile"
    }
    if ((Get-Content -Raw -LiteralPath $versionFile).Trim() -ne $ExpectedVersion) {
        throw "インストール済みバージョンが $ExpectedVersion ではありません"
    }
}

function Assert-ShortcutTargetsApplicationRoot {
    param([Parameter(Mandatory)][string]$ExpectedRoot)

    $shell = New-Object -ComObject WScript.Shell
    try {
        $expectedTarget = Join-Path $ExpectedRoot 'jre\bin\javaw.exe'
        $expectedLauncherJar = Join-Path $ExpectedRoot 'NicoCacheLauncher.jar'
        foreach ($shortcutPath in @($startMenuShortcut, $desktopShortcut)) {
            if (-not (Test-Path -LiteralPath $shortcutPath -PathType Leaf)) {
                throw "MSIショートカットがありません: $shortcutPath"
            }
            $shortcut = $shell.CreateShortcut($shortcutPath)
            try {
                if (-not [string]::Equals(
                        [System.IO.Path]::GetFullPath($shortcut.TargetPath),
                        [System.IO.Path]::GetFullPath($expectedTarget),
                        [System.StringComparison]::OrdinalIgnoreCase
                    ) -or
                        -not [string]::Equals(
                            [System.IO.Path]::GetFullPath(
                                $shortcut.WorkingDirectory
                            ).TrimEnd('\'),
                            [System.IO.Path]::GetFullPath(
                                $ExpectedRoot
                            ).TrimEnd('\'),
                            [System.StringComparison]::OrdinalIgnoreCase
                        ) -or
                        $shortcut.Arguments -notmatch '(?i)-jar' -or
                        $shortcut.Arguments -notmatch [regex]::Escape(
                            $expectedLauncherJar
                        )) {
                    throw (
                        "MSIショートカットが移行先を参照していません: " +
                        "$shortcutPath"
                    )
                }
            } finally {
                [Runtime.InteropServices.Marshal]::FinalReleaseComObject(
                    $shortcut
                ) | Out-Null
            }
        }
    } finally {
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) |
            Out-Null
    }
}

function Assert-RegisteredInstallRoot {
    param([Parameter(Mandatory)][string]$ExpectedRoot)

    $registeredInstallRoot = Get-ItemProperty `
        -LiteralPath 'HKCU:\Software\NicoCache_nl\Installer' `
        -Name InstallDir `
        -ErrorAction Stop |
        Select-Object -ExpandProperty InstallDir
    if (-not [string]::Equals(
            [System.IO.Path]::GetFullPath($registeredInstallRoot).TrimEnd('\'),
            [System.IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\'),
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "MSIのInstallDirが期待先ではありません: $registeredInstallRoot"
    }
}

function Assert-InstallRootRemoved {
    param([Parameter(Mandatory)][string]$ApplicationRoot)

    if (Test-Path -LiteralPath $ApplicationRoot) {
        $remnants = @(Get-ChildItem -LiteralPath $ApplicationRoot `
                -Recurse -Force)
        if ($remnants.Count -gt 0) {
            throw (
                "MSI処理後に導入先の残骸があります: $ApplicationRoot`n" +
                ($remnants.FullName -join "`n")
            )
        }
        throw "MSI処理後に空の導入先が残っています: $ApplicationRoot"
    }
}

function Invoke-LocationUpgradeCase {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('LegacyWithoutConfig', 'Custom')]
        [string]$Case
    )

    $isCustom = $Case -eq 'Custom'
    $previousRoot = if ($isCustom) {
        $customInstallRoot
    } else {
        $legacyInstallRoot
    }
    $expectedRoot = if ($isCustom) {
        $customInstallRoot
    } else {
        $installRoot
    }
    $logSuffix = if ($isCustom) { 'custom' } else { 'legacy-no-config' }
    $caseInstalled = $false
    $caseUpgraded = $false
    $caseFailure = $null
    try {
        $previousArguments = @(
            '/i', "`"$resolvedPreviousMsi`"", '/qn', '/norestart'
        )
        if ($isCustom) {
            $previousArguments += "INSTALLFOLDER=`"$customInstallRoot`""
        }
        Invoke-MsiExec -ArgumentList $previousArguments `
            -FailureMessage "旧版MSIの${Case}試験導入に失敗しました" `
            -LogPath (Join-Path $testRoot "msi-$logSuffix-install.log")
        $caseInstalled = $true
        Assert-AppVersion -ExpectedVersion $ExpectedPreviousVersion `
            -ApplicationRoot $previousRoot
        Assert-ShortcutTargetsApplicationRoot -ExpectedRoot $previousRoot
        Assert-RegisteredInstallRoot -ExpectedRoot $previousRoot

        $caseConfigPath = Join-Path $previousRoot 'config.properties'
        if ($isCustom) {
            Set-Content -LiteralPath $caseConfigPath `
                -Value 'installerLocationMarker=preserve-custom-location' `
                -Encoding ascii
        } elseif (Test-Path -LiteralPath $caseConfigPath) {
            throw '未起動旧版の試験前にconfig.propertiesが存在します'
        }

        Invoke-MsiExec -ArgumentList @(
            '/i', "`"$resolvedMsi`"", '/qn', '/norestart'
        ) `
            -FailureMessage "新版MSIへの${Case}試験更新に失敗しました" `
            -LogPath (Join-Path $testRoot "msi-$logSuffix-upgrade.log")
        $caseUpgraded = $true
        Assert-AppVersion -ExpectedVersion $ExpectedCurrentVersion `
            -ApplicationRoot $expectedRoot
        Assert-NoInstalledProcess -ApplicationRoots @(
            $previousRoot, $expectedRoot
        )
        Assert-ShortcutTargetsApplicationRoot -ExpectedRoot $expectedRoot
        Assert-RegisteredInstallRoot -ExpectedRoot $expectedRoot

        if ($isCustom) {
            if ((Get-Content -Raw -LiteralPath (
                        Join-Path $expectedRoot 'config.properties'
                    )) -notmatch 'preserve-custom-location') {
                throw '任意カスタム先のconfig.propertiesが更新で失われました'
            }
            Assert-InstallRootRemoved -ApplicationRoot $installRoot
            Assert-InstallRootRemoved -ApplicationRoot $legacyInstallRoot
            Write-Output 'PASS 任意カスタムINSTALLFOLDERの更新・設定保全'
        } else {
            if (Test-Path -LiteralPath (
                    Join-Path $expectedRoot 'config.properties'
                )) {
                throw '未起動旧版の更新でconfig.propertiesが意図せず作成されました'
            }
            Assert-InstallRootRemoved -ApplicationRoot $legacyInstallRoot
            Write-Output 'PASS config.propertiesなしの旧誤既定先からの更新'
        }
    } catch {
        $caseFailure = $_
    } finally {
        if ($caseInstalled) {
            $caseProductCode = if ($caseUpgraded) {
                $currentProductCode
            } else {
                $previousProductCode
            }
            try {
                Invoke-MsiExec -ArgumentList @(
                    '/x', $caseProductCode, '/qn', '/norestart'
                ) `
                    -FailureMessage "${Case}試験のアンインストールに失敗しました" `
                    -LogPath (Join-Path $testRoot (
                            "msi-$logSuffix-uninstall.log"
                        ))
            } catch {
                if ($caseFailure) {
                    throw (
                        "${Case}試験の最初の失敗:`n" +
                        "$($caseFailure.Exception.Message)`n" +
                        "後始末の失敗:`n$($_.Exception.Message)"
                    )
                }
                throw
            }
        }
    }
    if ($caseFailure) { throw $caseFailure }
    Assert-InstallRootRemoved -ApplicationRoot $expectedRoot
    if (-not $isCustom) {
        Assert-InstallRootRemoved -ApplicationRoot $previousRoot
    }
    if ((Test-Path -LiteralPath $startMenuShortcut) -or
            (Test-Path -LiteralPath $desktopShortcut)) {
        throw "${Case}試験のアンインストール後にショートカットが残っています"
    }
}

$osStateBefore = Get-OsIntegrationState
$previousProductCode = Get-MsiProductCode -Path $resolvedPreviousMsi
$currentProductCode = Get-MsiProductCode -Path $resolvedMsi
Invoke-LocationUpgradeCase -Case LegacyWithoutConfig
Invoke-LocationUpgradeCase -Case Custom
$installed = $false
$upgraded = $false
$userStatePath = Join-Path $userDataRoot 'data\installer-lifecycle-user.txt'
$applicationConfigPath = Join-Path $legacyInstallRoot 'config.properties'
$setupStatePath = Join-Path $userDataRoot 'data\setup-system-state.json'
$primaryFailure = $null

try {
    Invoke-MsiExec -ArgumentList @(
        '/i',
        "`"$resolvedPreviousMsi`"",
        '/qn',
        '/norestart'
    ) `
        -FailureMessage '旧版MSIの無人インストールに失敗しました' `
        -LogPath (Join-Path $testRoot 'msi-install-previous.log')
    $installed = $true

    if (-not (Test-Path -LiteralPath $legacyInstallRoot -PathType Container)) {
        throw "旧版MSIが誤既定先へインストールされませんでした: $legacyInstallRoot"
    }
    Assert-AppVersion -ExpectedVersion $ExpectedPreviousVersion `
        -ApplicationRoot $legacyInstallRoot
    Assert-NoInstalledProcess
    if (-not (Test-Path -LiteralPath $startMenuShortcut -PathType Leaf)) {
        throw "スタートメニューのショートカットがありません: $startMenuShortcut"
    }
    if (-not (Test-Path -LiteralPath $desktopShortcut -PathType Leaf)) {
        throw "デスクトップのショートカットがありません: $desktopShortcut"
    }
    Assert-ShortcutTargetsApplicationRoot -ExpectedRoot $legacyInstallRoot
    Write-Output 'PASS 旧版MSIの誤既定先導入・ショートカット・起動抑止'

    New-Item -ItemType Directory -Path (Split-Path -Parent $userStatePath) `
        -Force | Out-Null
    Set-Content -LiteralPath $userStatePath `
        -Value 'preserve-user-state-across-repair-and-upgrade' `
        -Encoding ascii
    Set-Content -LiteralPath $applicationConfigPath `
        -Value @(
            "userDataRoot=$($userDataRoot.Replace('\', '\\'))"
            'installerLifecycleMarker=preserve-application-config'
        ) `
        -Encoding ascii

    $repairTarget = Join-Path $legacyInstallRoot 'nlFilter_sys.txt'
    if (-not (Test-Path -LiteralPath $repairTarget -PathType Leaf)) {
        throw "修復対象ファイルがありません: $repairTarget"
    }
    Remove-Item -LiteralPath $repairTarget -Force
    $repairLogPath = Join-Path $testRoot 'msi-repair.log'
    Invoke-MsiExec -ArgumentList @(
        '/i',
        "`"$resolvedPreviousMsi`"",
        '/qn',
        '/norestart',
        'REINSTALL=ALL',
        'REINSTALLMODE=amus'
    ) `
        -FailureMessage 'MSI修復に失敗しました' `
        -LogPath $repairLogPath
    if (-not (Test-Path -LiteralPath $repairTarget -PathType Leaf)) {
        $repairLogDetails = @(
            Select-String -LiteralPath $repairLogPath `
                -Pattern 'nlFilter_sys|REINSTALL|INSTALLDIR|File:|Component:' `
                -ErrorAction SilentlyContinue |
                Select-Object -Last 120 |
                ForEach-Object { $_.Line }
        ) -join "`n"
        throw (
            "MSI修復で配布ファイルが復元されませんでした`n" +
            "MSIログ: $repairLogPath`n$repairLogDetails"
        )
    }
    if (-not (Test-Path -LiteralPath $desktopShortcut -PathType Leaf)) {
        throw 'MSI修復後にデスクトップのショートカットがありません'
    }
    if ((Get-Content -Raw -LiteralPath $userStatePath).Trim() -ne
            'preserve-user-state-across-repair-and-upgrade') {
        throw 'MSI修復でユーザー状態が変化しました'
    }
    if ((Get-Content -Raw -LiteralPath $applicationConfigPath) -notmatch
            'installerLifecycleMarker=preserve-application-config') {
        throw 'MSI修復でアプリ側config.propertiesが失われました'
    }
    Assert-ShortcutTargetsApplicationRoot -ExpectedRoot $legacyInstallRoot
    Write-Output 'PASS MSI修復とユーザー状態の保全'

    Invoke-MsiExec -ArgumentList @(
        '/i',
        "`"$resolvedMsi`"",
        '/qn',
        '/norestart'
    ) `
        -FailureMessage '新版MSIへの無人更新に失敗しました' `
        -LogPath (Join-Path $testRoot 'msi-upgrade.log')
    $upgraded = $true
    Assert-AppVersion -ExpectedVersion $ExpectedCurrentVersion `
        -ApplicationRoot $installRoot
    Assert-NoInstalledProcess
    $applicationConfigPath = Join-Path $installRoot 'config.properties'
    if (-not (Test-Path -LiteralPath $desktopShortcut -PathType Leaf)) {
        throw 'MSI更新後にデスクトップのショートカットがありません'
    }
    if ((Get-Content -Raw -LiteralPath $userStatePath).Trim() -ne
            'preserve-user-state-across-repair-and-upgrade') {
        throw 'MSI更新でユーザー状態が失われました'
    }
    if ((Get-Content -Raw -LiteralPath $applicationConfigPath) -notmatch
            'installerLifecycleMarker=preserve-application-config') {
        throw 'MSI更新でアプリ側config.propertiesが失われました'
    }
    Assert-InstallRootRemoved -ApplicationRoot $legacyInstallRoot
    Assert-RegisteredInstallRoot -ExpectedRoot $installRoot
    Assert-ShortcutTargetsApplicationRoot -ExpectedRoot $installRoot
    Write-Output 'PASS 旧誤既定先から新版既定先への移行とユーザー状態の保全'

    Remove-Item -LiteralPath $applicationConfigPath -Force
    & (Join-Path $PSScriptRoot 'test-windows-app-image.ps1') `
        -AppImagePath $installRoot `
        -StartupTimeoutSeconds $StartupTimeoutSeconds `
        -AllowInstalledApplication
    Write-Output 'PASS 更新後MSIの隔離起動'

    $certificateDirectory = Join-Path $userDataRoot 'certs'
    $launcher = Join-Path $installRoot 'jre\bin\java.exe'
    $autoStartJava = Join-Path $installRoot 'jre\bin\javaw.exe'
    $launcherJar = Join-Path $installRoot 'NicoCacheLauncher.jar'
    $setupProcess = Start-Process `
        -FilePath $launcher `
        -ArgumentList @(
            '-jar',
            $launcherJar,
            '--setup',
            '--headless',
            "--user-data-root=$userDataRoot",
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
    $registeredStatePath = Get-ItemProperty `
        -LiteralPath $stateLocatorRegistryPath `
        -Name $stateLocatorValueName `
        -ErrorAction Stop |
        Select-Object -ExpandProperty $stateLocatorValueName
    if ($registeredStatePath -ne $setupStatePath) {
        throw "Windows設定状態の保存先登録が一致しません: $registeredStatePath"
    }
    $proxy = Get-ItemProperty -LiteralPath (
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    )
    if ($proxy.AutoConfigURL -ne 'http://localhost:8080/proxy.pac') {
        throw 'MSI上の初回セットアップで自動プロキシーが設定されませんでした'
    }
    $runValue = (Get-ItemProperty -LiteralPath $runRegistryPath `
            -Name $runValueName).$runValueName
    if (($runValue -notmatch [regex]::Escape($autoStartJava)) -or
            ($runValue -notmatch [regex]::Escape($launcherJar)) -or
            ($runValue -notmatch '(?i)-jar')) {
        throw 'MSI上の自動起動が同梱JREとランチャーJARを参照していません'
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
} catch {
    $primaryFailure = $_
} finally {
    if ($installed) {
        $uninstallProductCode = if ($upgraded) {
            $currentProductCode
        } else {
            $previousProductCode
        }
        try {
            Invoke-MsiExec -ArgumentList @(
                '/x',
                $uninstallProductCode,
                '/qn',
                '/norestart'
            ) `
                -FailureMessage 'MSIの無人アンインストールに失敗しました' `
                -LogPath (Join-Path $testRoot 'msi-uninstall.log')
        } catch {
            if ($primaryFailure) {
                throw (
                    "MSIライフサイクルの最初の失敗:`n" +
                    "$($primaryFailure.Exception.Message)`n" +
                    "後始末の失敗:`n$($_.Exception.Message)"
                )
            }
            throw
        }
    }
}

if ($primaryFailure) {
    throw $primaryFailure
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
    throw (
        "MSI試験の前後でOS統合状態が変化しました`n" +
        "変更前: $osStateBefore`n変更後: $osStateAfter"
    )
}
if (-not (Test-Path -LiteralPath $userStatePath -PathType Leaf)) {
    throw 'アンインストール後に利用者データが失われました'
}
if (Test-Path -LiteralPath $setupStatePath) {
    throw 'アンインストール後にWindows設定の復元状態が残っています'
}
Write-Output 'PASS MSIの無人アンインストール、利用者データ保持、OS統合状態の復元'

if (Test-Path -LiteralPath $userDataRoot) {
    Remove-Item -LiteralPath $userDataRoot -Recurse -Force
}
