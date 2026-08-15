[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$AppImagePath,

    [ValidateRange(5, 120)]
    [int]$StartupTimeoutSeconds = 30,

    [switch]$AllowInstalledApplication,

    [switch]$KeepLogs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $root '.test-work')).
    TrimEnd([System.IO.Path]::DirectorySeparatorChar)
$appImage = (Resolve-Path -LiteralPath $AppImagePath).Path
$isTestImage = $appImage.StartsWith(
        $testRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)
$isVerifiedInstalledImage = $false
if ($AllowInstalledApplication -and $env:GITHUB_ACTIONS -eq 'true' -and
        -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $expectedInstalledRoot = [IO.Path]::GetFullPath(
        (Join-Path $env:LOCALAPPDATA 'NicoCache_nl')
    ).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $installerState = Get-ItemProperty `
        -LiteralPath 'HKCU:\Software\NicoCache_nl\Installer' `
        -ErrorAction SilentlyContinue
    $registeredInstallRoot = if ($installerState -and
            $installerState.InstallDir) {
        [IO.Path]::GetFullPath([string]$installerState.InstallDir).
            TrimEnd([IO.Path]::DirectorySeparatorChar)
    } else { '' }
    $isVerifiedInstalledImage =
        [string]::Equals(
            $appImage, $expectedInstalledRoot,
            [StringComparison]::OrdinalIgnoreCase
        ) -and [string]::Equals(
            $appImage, $registeredInstallRoot,
            [StringComparison]::OrdinalIgnoreCase
        )
}
if (-not $isTestImage -and -not $isVerifiedInstalledImage) {
    throw "実環境の誤操作を防ぐため.test-work外のイメージは検証できません: $appImage"
}
$appDirectory = $appImage
$launcherJarPath = Join-Path $appImage 'NicoCacheLauncher.jar'
$diagnosticsJarPath = Join-Path $appImage 'NicoCacheDiagnostics.jar'
$launcherPath = Join-Path $appImage 'jre\bin\java.exe'
$diagnosticsJavaPath = Join-Path $appImage 'jre\bin\javaw.exe'
$coreJavaPath = $launcherPath
$separateHeadlessLauncherPath = Join-Path $appImage 'NicoCache_nl-Headless.exe'
$dataRoot = Join-Path $testRoot 'app-image-user-data'
$configPath = Join-Path $appDirectory 'config.properties'
$guiPropertiesPath = Join-Path $dataRoot 'NicoCacheGUI.property'
$setupStatePath = Join-Path $dataRoot 'data\first-run-setup.properties'
$diagnosticsStatusPath = Join-Path $dataRoot `
    'data\nicocache-diagnostics-status.properties'
$diagnosticsLockPath = Join-Path $dataRoot `
    'data\nicocache-diagnostics.lock'
$controlStatusPath = Join-Path $dataRoot `
    'data\nicocache-control.properties'
$incidentRoot = Join-Path $dataRoot 'diagnostics\incidents'
$systemStatePath = Join-Path $dataRoot 'data\setup-system-state.json'
$setupScriptPath = Join-Path $appDirectory 'setup\windows\first-run-setup.ps1'
$certificateDirectory = Join-Path $dataRoot 'certs'
$certificateTargetsPath = Join-Path $appDirectory 'certificate-targets.txt'
$logRoot = Join-Path $appImage 'smoke-test-logs'
$stdoutPath = Join-Path $logRoot 'stdout.log'
$stderrPath = Join-Path $logRoot 'stderr.log'
$setupStdoutPath = Join-Path $logRoot 'setup-stdout.log'
$setupStderrPath = Join-Path $logRoot 'setup-stderr.log'
$foreignWorkingDirectory = Join-Path $logRoot 'foreign-working-directory'

foreach ($requiredPath in @(
        $launcherPath,
        (Join-Path $appDirectory 'jre\bin\java.exe'),
        (Join-Path $appDirectory 'jre\bin\javaw.exe'),
        (Join-Path $appDirectory 'jre\bin\jcmd.exe'),
        (Join-Path $appDirectory 'NicoCache_nl.cmd'),
        (Join-Path $appDirectory 'NicoCacheDiagnostics.cmd'),
        (Join-Path $appDirectory 'NicoCache_nl.jar'),
        (Join-Path $appDirectory 'NicoCacheCA.jar'),
        (Join-Path $appDirectory 'NicoCacheLauncher.jar'),
        $diagnosticsJarPath,
        (Join-Path $appDirectory 'NicoCacheBuild.jar'),
        $certificateTargetsPath,
        (Join-Path $appDirectory 'lib\bcprov.jar'),
        (Join-Path $appDirectory 'lib\bcpkix.jar'),
        (Join-Path $appDirectory 'lib\bcutil.jar'),
        (Join-Path $appDirectory 'lib\brotli-dec.jar'),
        (Join-Path $appDirectory 'lib\zstd-jni.jar'),
        (Join-Path $appDirectory 'setup\windows\first-run-setup.ps1'),
        (Join-Path $appDirectory 'defaults\application.properties'),
        (Join-Path $appDirectory 'defaults\network.properties'),
        (Join-Path $appDirectory 'defaults\video-cache.properties'),
        (Join-Path $appDirectory 'defaults\legacy-cache-compatibility.properties'),
        (Join-Path $appDirectory 'defaults\rewriting.properties'),
        (Join-Path $appDirectory 'defaults\thumbnail-cache.properties'),
        (Join-Path $appDirectory 'defaults\https-mitm.properties'),
        (Join-Path $appDirectory 'data\cors\99_sample.conf'),
        (Join-Path $appDirectory 'data\tlsclient\cacerts2'),
        (Join-Path $appDirectory 'local\mime.types.default'),
        (Join-Path $appDirectory 'how-to-update.md'),
        (Join-Path $appDirectory 'documents\api.md'),
        (Join-Path $appDirectory 'documents\diagnostics-watchdog.md'),
        (Join-Path $appDirectory 'documents\tls.md'),
        (Join-Path $appDirectory 'documents\user-data-root.md'),
        (Join-Path $appDirectory 'packaging\windows\README.md'),
        (Join-Path $appDirectory 'packaging\unix\README.md'),
        (Join-Path $appDirectory 'tests\README.md'),
        (Join-Path $appDirectory 'nlFilters\how-to-update.md'),
        (Join-Path $appDirectory 'nlFilters\tools\nlfilter-lab\README.md'),
        (Join-Path $appDirectory 'tools\cmaf-to-mp4\nico-cmaf-to-mp4.jar'),
        (Join-Path $appDirectory 'tools\cmaf-to-mp4\README.md'),
        (Join-Path $appDirectory 'build-javac.ps1'),
        (Join-Path $appDirectory 'src\dareka\NLMain.java'),
        (Join-Path $appDirectory 'tests\functional\FunctionalTestMain.java'),
        (Join-Path $appDirectory 'how-to-dump-stack-trace.md')
    )) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "アプリイメージに必要なファイルがありません: $requiredPath"
    }
}
$systemFilesManifest = Join-Path $root 'packaging\system-files.txt'
$systemFiles = @(
    Get-Content -LiteralPath $systemFilesManifest |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') }
)
foreach ($relativePath in $systemFiles) {
    $systemPath = Join-Path $appDirectory ($relativePath -replace '/', '\')
    if (-not (Test-Path -LiteralPath $systemPath -PathType Leaf)) {
        throw "システム資材がアプリイメージにありません: $relativePath"
    }
}
foreach ($userOnlyDirectory in @('cache', 'cvcache', 'thcache')) {
    if (Test-Path -LiteralPath (
            Join-Path $appDirectory $userOnlyDirectory
        )) {
        throw "ユーザー専用ディレクトリがアプリ側にあります: $userOnlyDirectory"
    }
}
$applicationCertificateFiles = @(Get-ChildItem -LiteralPath (
        Join-Path $appDirectory 'certs'
    ) -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
if ($applicationCertificateFiles.Count -ne 1 -or
        $applicationCertificateFiles[0] -ne 'readme.txt') {
    throw "アプリ側certsにはclone由来のreadme.txtだけを配置できます: $applicationCertificateFiles"
}
$jarPath = Join-Path $appDirectory 'NicoCache_nl.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $jarEntries = @($archive.Entries | Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'dareka/UserDataMain.class',
            'dareka/NicoCachePaths.class',
            'dareka/FirstRunSetup.class',
            'dareka/FirstRunWizard.class',
            'dareka/FirstRunWizard$FirstRunWizardPanel.class',
            'dareka/setup_messages.properties',
            'dareka/setup_messages_ja.properties'
        )) {
        if ($requiredEntry -notin $jarEntries) {
            throw "パッケージJARに初回ウィザード要素がありません: $requiredEntry"
        }
    }
} finally {
    $archive.Dispose()
}
$launcherArchive = [System.IO.Compression.ZipFile]::OpenRead($launcherJarPath)
try {
    $launcherEntries = @($launcherArchive.Entries |
        Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'nicocache/launcher/LauncherMain.class',
            'nicocache/launcher/LauncherLifecycle.class',
            'nicocache/launcher/messages.properties',
            'nicocache/launcher/messages_ja.properties'
        )) {
        if ($requiredEntry -notin $launcherEntries) {
            throw "ランチャーJARに必要な要素がありません: $requiredEntry"
        }
    }
} finally {
    $launcherArchive.Dispose()
}
$diagnosticsArchive = [System.IO.Compression.ZipFile]::OpenRead(
    $diagnosticsJarPath)
try {
    $diagnosticsEntries = @($diagnosticsArchive.Entries |
        Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'nicocache/diagnostics/DiagnosticsMain.class',
            'nicocache/diagnostics/messages.properties',
            'nicocache/diagnostics/messages_ja.properties'
        )) {
        if ($requiredEntry -notin $diagnosticsEntries) {
            throw "診断アプリJARに必要な要素がありません: $requiredEntry"
        }
    }
} finally {
    $diagnosticsArchive.Dispose()
}
$buildJarPath = Join-Path $appDirectory 'NicoCacheBuild.jar'
$buildArchive = [System.IO.Compression.ZipFile]::OpenRead($buildJarPath)
try {
    $buildEntries = @($buildArchive.Entries | Select-Object -ExpandProperty FullName)
    if ('nicocache/build/BuildMain.class' -notin $buildEntries) {
        throw 'ビルド管理JARに必要なエントリがありません: nicocache/build/BuildMain.class'
    }
} finally {
    $buildArchive.Dispose()
}
$cmafJarPath = Join-Path $appDirectory 'tools\cmaf-to-mp4\nico-cmaf-to-mp4.jar'
$cmafArchive = [System.IO.Compression.ZipFile]::OpenRead($cmafJarPath)
try {
    $cmafEntries = @($cmafArchive.Entries | Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'META-INF/MANIFEST.MF',
            'nicocache/cmaftomp4/Main.class'
        )) {
        if ($requiredEntry -notin $cmafEntries) {
            throw "同梱CMAF/Domand変換アプリJARに必要な要素がありません: $requiredEntry"
        }
    }
} finally {
    $cmafArchive.Dispose()
}
$keytool = (Get-Command keytool -ErrorAction Stop).Source
& $keytool -list `
    -keystore (Join-Path $appDirectory 'data\tlsclient\cacerts2') `
    -storepass NicoCache |
    Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'パッケージのTLSクライアント用トラストストアを読み込めません'
}
if (Test-Path -LiteralPath $separateHeadlessLauncherPath) {
    throw "GUI用とヘッドレス用の製品ランチャーが分離されています: $separateHeadlessLauncherPath"
}
$rootLaunchers = @(Get-ChildItem -LiteralPath $appImage -File -Filter 'NicoCache_nl.*' |
    Where-Object { $_.Extension -in @('.cmd', '.exe') } |
    Select-Object -ExpandProperty Name)
if ($rootLaunchers.Count -ne 1 -or $rootLaunchers[0] -ne 'NicoCache_nl.cmd') {
    throw "アプリの起動入口が1本ではありません: $($rootLaunchers -join ', ')"
}
if (Test-Path -LiteralPath $configPath) {
    throw "既存設定を上書きしないためテストを中止します: $configPath"
}
if (Test-Path -LiteralPath $dataRoot) {
    $resolvedDataRoot = (Resolve-Path -LiteralPath $dataRoot).Path
    if (-not $resolvedDataRoot.StartsWith(
            $testRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でない利用者データ試験パスです: $resolvedDataRoot"
    }
    Remove-Item -LiteralPath $resolvedDataRoot -Recurse -Force
}
$certificateTargets = @(
    Get-Content -LiteralPath $certificateTargetsPath |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') }
)
if ($certificateTargets.Count -eq 0) {
    throw "証明書対象一覧が空です: $certificateTargetsPath"
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
    $certificateThumbprints = @(Get-ChildItem -LiteralPath 'Cert:\CurrentUser\Root' |
        Select-Object -ExpandProperty Thumbprint |
        Sort-Object)
    return [PSCustomObject]@{
        Proxy = $proxyState
        CertificateThumbprints = $certificateThumbprints
    } | ConvertTo-Json -Depth 4 -Compress
}

function Get-ProductProcesses {
    return @(
        Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                try {
                    if (-not $_.Path) { return $false }
                    [string]::Equals(
                        $_.Path, $launcherPath,
                        [System.StringComparison]::OrdinalIgnoreCase
                    ) -or [string]::Equals(
                        $_.Path, $coreJavaPath,
                        [System.StringComparison]::OrdinalIgnoreCase
                    ) -or [string]::Equals(
                        $_.Path, $diagnosticsJavaPath,
                        [System.StringComparison]::OrdinalIgnoreCase
                    )
                } catch {
                    $false
                }
            }
    )
}

function Wait-DiagnosticsProcess {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if (Test-Path -LiteralPath $diagnosticsStatusPath -PathType Leaf) {
            try {
                $status = Get-Content -Raw -LiteralPath `
                    $diagnosticsStatusPath | ConvertFrom-StringData
                $diagnosticsProcess = Get-Process -Id ([int]$status.pid) `
                    -ErrorAction Stop
                if (-not [string]::Equals(
                        $diagnosticsProcess.Path, $diagnosticsJavaPath,
                        [System.StringComparison]::OrdinalIgnoreCase)) {
                    throw "診断アプリの実行ファイルが同梱JREではありません: $($diagnosticsProcess.Path)"
                }
                return $diagnosticsProcess
            } catch [Microsoft.PowerShell.Commands.ProcessCommandException] {
                # 状態ファイルとプロセス生成の境界なら再試行する。
            }
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw '常駐診断アプリが10秒以内に起動しませんでした'
}

$osStateBefore = Get-OsIntegrationState
$initialCertificateFiles = @(
    Get-ChildItem -LiteralPath $certificateDirectory -File -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
)

$listener = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0
)
$listener.Start()
$listenPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

New-Item -ItemType Directory -Path $logRoot, $foreignWorkingDirectory -Force |
    Out-Null

$process = $null
$setupProcess = $null
$guiProcess = $null
$knownProcessIds = @(Get-ProductProcesses | Select-Object -ExpandProperty Id)
$testSucceeded = $false
try {
    $setupProcess = Start-Process -FilePath $launcherPath `
        -ArgumentList @(
            '-jar',
            $launcherJarPath,
            '--setup',
            '--headless',
            "--user-data-root=$dataRoot",
            '--https=true',
            '--trust-certificate=false',
            '--proxy=false',
            '--autostart=false'
        ) `
        -WorkingDirectory $appImage `
        -RedirectStandardOutput $setupStdoutPath `
        -RedirectStandardError $setupStderrPath `
        -Wait `
        -PassThru
    if ($setupProcess.ExitCode -ne 0) {
        throw "ヘッドレス初回セットアップに失敗しました (ExitCode: $($setupProcess.ExitCode))"
    }
    foreach ($forbiddenApplicationFile in @(
            (Join-Path $appDirectory 'NicoCacheGUI.property'),
            (Join-Path $appDirectory 'proxy.pac'),
            (Join-Path $appDirectory '.data-layout-version'),
            (Join-Path $dataRoot 'config.properties')
        )) {
        if (Test-Path -LiteralPath $forbiddenApplicationFile) {
            throw "利用者データがアプリ本体へ作成されました: $forbiddenApplicationFile"
        }
    }
    foreach ($createdPath in @(
            $configPath,
            $guiPropertiesPath,
            $setupStatePath
        )) {
        if (-not (Test-Path -LiteralPath $createdPath -PathType Leaf)) {
            throw "ヘッドレス初回セットアップの生成物がありません: $createdPath"
        }
    }
    foreach ($generatedCertificate in @('ca.cer', 'ca.jks', 'ca.pem',
            'site.cer', 'site.jks', 'site.targets')) {
        $generatedPath = Join-Path $certificateDirectory $generatedCertificate
        if (-not (Test-Path -LiteralPath $generatedPath -PathType Leaf)) {
            throw "隔離証明書生成物がありません: $generatedPath"
        }
    }
    Copy-Item -LiteralPath (Join-Path $appDirectory 'proxy_sample.pac') `
        -Destination (Join-Path $dataRoot 'proxy.pac') -Force
    $diagnosticOutput = & $launcherPath '-jar' $launcherJarPath `
        '--headless' '--check-data-root' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ユーザーデータルート診断が完全になりません: $diagnosticOutput"
    }
    $actualCertificateTargets = @(
        Get-Content -LiteralPath (
            Join-Path $certificateDirectory 'site.targets'
        ) |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    if (Compare-Object $certificateTargets $actualCertificateTargets) {
        throw '生成したサイト証明書の対象が配布一覧と一致しません'
    }
    $setupState = Get-Content -Raw -LiteralPath $setupStatePath |
        ConvertFrom-StringData
    if ($setupState.trustCertificate -ne 'false') {
        throw 'ヘッドレス初回セットアップでCA登録無効が保存されていません'
    }
    if (Test-Path -LiteralPath $systemStatePath) {
        throw 'OS連携を全項目無効にしたのにシステム状態が作成されました'
    }
    Write-Output 'PASS 同梱JREとランチャーJARによるOSへ登録しないヘッドレス初回セットアップ'
    Write-Output 'PASS 本番対象を使った隔離証明書生成'

    @(
        '# NicoCache_nl パッケージ隔離スモークテスト用設定'
        "userDataRoot=$($dataRoot.Replace('\', '\\'))"
        "listenPort=$listenPort"
        'proxyHost='
        'allowFrom=local'
        'localFileServer=true'
        'enableMitm=false'
        'title=false'
        ''
    ) | Set-Content -LiteralPath $configPath -Encoding utf8

    $helpOutput = @(& $coreJavaPath '-jar' $launcherJarPath '--help' 2>&1)
    if ($LASTEXITCODE -ne 0 -or
            -not (($helpOutput -join "`n").Contains('--tray')) -or
            -not (($helpOutput -join "`n").Contains('--minimized')) -or
            -not (($helpOutput -join "`n").Contains(
                'core and diagnostics stop together; resident launcher continues'))) {
        throw "Windowsランチャーの表示モードCLIが不正です: $helpOutput"
    }

    $guiProcess = Start-Process -FilePath $launcherPath `
        -ArgumentList @('-jar', $launcherJarPath) `
        -WorkingDirectory $appImage `
        -PassThru
    Start-Sleep -Seconds 3
    if ($guiProcess.HasExited) {
        throw "引数なしの起動管理GUIが終了しました (ExitCode: $($guiProcess.ExitCode))"
    }
    $implicitCoreProcesses = @(
        Get-ProductProcesses |
            Where-Object {
                try {
                    $native = Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)"
                    $native.CommandLine -match '(?i)NicoCache_nl\.jar'
                } catch {
                    $false
                }
            }
    )
    if ($implicitCoreProcesses.Count -ne 0) {
        throw '引数なしの起動管理GUIがNicoCache_nl本体を暗黙起動しました'
    }
    if (Test-Path -LiteralPath $diagnosticsStatusPath -PathType Leaf) {
        throw '本体未起動の起動管理GUIが診断アプリを暗黙起動しました'
    }

    $startOutput = @(& $launcherPath '-jar' $launcherJarPath `
        '--headless' '--start' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "常駐ランチャー併用時の本体起動に失敗しました: $startOutput"
    }
    if (-not (Test-Path -LiteralPath $controlStatusPath -PathType Leaf)) {
        throw 'ヘッドレス起動後に本体の管理状態ファイルがありません'
    }
    $controlStatus = Get-Content -Raw -LiteralPath $controlStatusPath |
        ConvertFrom-StringData
    $coreProcess = Get-Process -Id ([int]$controlStatus.pid) -ErrorAction Stop
    $diagnosticsProcess = Wait-DiagnosticsProcess
    $residentLauncherId = $guiProcess.Id
    $residentDiagnosticsId = $diagnosticsProcess.Id

    Start-Sleep -Milliseconds 2500
    $existingIncidentReports = @(Get-ChildItem -LiteralPath $incidentRoot `
        -Recurse -File -Filter 'report.html' -ErrorAction SilentlyContinue)
    if ($existingIncidentReports.Count -ne 0) {
        throw '正常起動だけで診断インシデントが生成されました'
    }
    $originalControlStatus = [IO.File]::ReadAllBytes($controlStatusPath)
    $faultListener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $faultListener.Start()
    $unreachableControlPort = (
        [Net.IPEndPoint]$faultListener.LocalEndpoint).Port
    $faultListener.Stop()
    $propertiesEncoding = [Text.Encoding]::GetEncoding('iso-8859-1')
    $statusText = $propertiesEncoding.GetString($originalControlStatus)
    $faultedStatusText = $statusText -replace `
        '(?m)^port=\d+\r?$', "port=$unreachableControlPort"
    if ($faultedStatusText -eq $statusText) {
        throw '診断故障注入用の管理ポートを置換できませんでした'
    }
    $faultStatusTemporary = "$controlStatusPath.fault.tmp"
    try {
        [IO.File]::WriteAllText($faultStatusTemporary,
            $faultedStatusText, $propertiesEncoding)
        [IO.File]::Move($faultStatusTemporary, $controlStatusPath, $true)

        $incidentDeadline = [DateTime]::UtcNow.AddSeconds(45)
        $incidentReport = $null
        do {
            $incidentReport = @(Get-ChildItem -LiteralPath $incidentRoot `
                -Recurse -File -Filter 'report.html' `
                -ErrorAction SilentlyContinue | Select-Object -First 1)
            if ($incidentReport.Count -eq 0) {
                Start-Sleep -Milliseconds 100
            }
        } while ($incidentReport.Count -eq 0 -and
            [DateTime]::UtcNow -lt $incidentDeadline)
        if ($incidentReport.Count -eq 0) {
            throw '同梱JREで管理API断の自動診断レポートが生成されませんでした'
        }
        $incidentHtml = Get-Content -Raw -LiteralPath `
            $incidentReport[0].FullName -Encoding utf8
        foreach ($requiredReportText in @(
                'control-heartbeat-lost',
                'Snapshot 1',
                'Snapshot 2',
                'Snapshot 3',
                'Full thread dump',
                '&lt;APP_ROOT&gt;',
                '&lt;DATA_ROOT&gt;'
            )) {
            if (-not $incidentHtml.Contains($requiredReportText)) {
                throw "同梱JREの診断レポートに必要な内容がありません: $requiredReportText"
            }
        }
        foreach ($forbiddenReportText in @(
                $appImage,
                $dataRoot,
                [string]$controlStatus.token,
                '<script'
            )) {
            if (-not [string]::IsNullOrEmpty($forbiddenReportText) -and
                    $incidentHtml.Contains($forbiddenReportText)) {
                throw '同梱JREの診断レポートに非公開情報またはスクリプトが残りました'
            }
        }
        if ($incidentReport[0].Length -le 10000) {
            throw "同梱JREの診断レポートが小さすぎます: $($incidentReport[0].Length) bytes"
        }
        Write-Output 'PASS 同梱JREのjcmdで管理API断から3回のスレッドダンプを自動採取'
        Write-Output 'PASS 配布診断HTMLの自己完結性・匿名化・容量を検証'
    } finally {
        if (Test-Path -LiteralPath $faultStatusTemporary) {
            Remove-Item -LiteralPath $faultStatusTemporary -Force
        }
        $restoreStatusTemporary = "$controlStatusPath.restore.tmp"
        [IO.File]::WriteAllBytes($restoreStatusTemporary,
            $originalControlStatus)
        [IO.File]::Move($restoreStatusTemporary, $controlStatusPath, $true)
    }
    Start-Sleep -Seconds 7
    if ($coreProcess.HasExited) {
        throw '診断故障注入がNicoCache_nl本体を停止しました'
    }
    if ((Get-Process -Id $residentDiagnosticsId -ErrorAction Stop).HasExited) {
        throw '自動収集後に診断アプリが終了しました'
    }

    $stopOutput = @(& $launcherPath '-jar' $launcherJarPath `
        '--headless' '--stop' 2>&1)
    if ($LASTEXITCODE -ne 0 -or
            -not (($stopOutput -join "`n").Contains(
                'diagnostics stopped gracefully; resident launcher unchanged'))) {
        throw "常駐ランチャー併用時の本体停止に失敗しました: $stopOutput"
    }
    $coreProcess.WaitForExit(10000) | Out-Null
    $coreProcess.Refresh()
    if (-not $coreProcess.HasExited) {
        throw "--headless --stop後も本体が動作しています: $($coreProcess.Id)"
    }
    if ((Get-Process -Id $residentLauncherId -ErrorAction Stop).HasExited) {
        throw '--headless --stopが常駐ランチャーまで終了しました'
    }
    $diagnosticsProcess.WaitForExit(10000) | Out-Null
    $diagnosticsProcess.Refresh()
    if (-not $diagnosticsProcess.HasExited) {
        throw '--headless --stop後も診断アプリが動作しています'
    }
    if (Test-Path -LiteralPath $diagnosticsStatusPath -PathType Leaf) {
        throw '--headless --stop後も診断アプリの状態ファイルが残っています'
    }
    Start-Sleep -Milliseconds 2500
    $reportsAfterPlannedStop = @(Get-ChildItem -LiteralPath $incidentRoot `
        -Recurse -File -Filter 'report.html' -ErrorAction SilentlyContinue)
    if ($reportsAfterPlannedStop.Count -ne 1) {
        throw '--headless --stopを障害として誤記録しました'
    }
    Write-Output 'PASS 常駐ランチャー中の--headless --stopは本体と診断アプリを停止'
    Write-Output 'PASS 本体停止後も既存のランチャーだけが常駐'
    Write-Output 'PASS 計画停止は新しい障害レポートを生成しない'

    Stop-Process -Id $guiProcess.Id -Force
    $guiProcess.WaitForExit(10000) | Out-Null
    Write-Output 'PASS 引数なしのGUI起動では本体を暗黙起動しない'
    Write-Output 'PASS 本体の起動と正常終了に診断アプリが同期'

    $process = Start-Process -FilePath $launcherPath `
        -ArgumentList @('-jar', $launcherJarPath, '--headless') `
        -WorkingDirectory $foreignWorkingDirectory `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $response = $null
    do {
        $replacement = @(
            Get-ProductProcesses |
                Where-Object { $_.Id -notin $knownProcessIds } |
                Where-Object { -not $_.HasExited } |
                Select-Object -First 1
        )
        if ($replacement.Count -gt 0) {
            $process = $replacement[0]
        }
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$listenPort/" `
                -UseBasicParsing -TimeoutSec 2
        } catch {
            Start-Sleep -Milliseconds 250
        }
    } until ($response -or [DateTime]::UtcNow -ge $deadline)

    if (-not $response) {
        $exitDescription = if ($process.HasExited) {
            "最後のExitCode: $($process.ExitCode)"
        } else {
            'プロセスは実行中'
        }
        throw "異なる作業ディレクトリから起動した単一ランチャーが" +
            "${StartupTimeoutSeconds}秒以内に応答しませんでした ($exitDescription)"
    }
    if ($response.StatusCode -ne 200) {
        throw "予期しないHTTPステータスです: $($response.StatusCode)"
    }
    if ($response.Content -notmatch 'NicoCache_nl version') {
        throw 'ルート応答にNicoCache_nlのバージョン文字列がありません'
    }
    $headlessDiagnosticsProcess = Wait-DiagnosticsProcess

    Write-Output 'PASS 異なる作業ディレクトリからuserDataRootを解決して起動'
    Write-Output "PASS 単一製品ランチャーの内部ヘッドレス起動"
    Write-Output 'PASS ヘッドレス本体起動から常駐診断と自動監視を開始'
    Write-Output "PASS HTTPループバック応答 (port=$listenPort)"
    $testSucceeded = $true
} finally {
    $startedProcesses = @(
        Get-ProductProcesses |
            Where-Object { $_.Id -notin $knownProcessIds }
    )
    $expectedPaths = @(
        (Resolve-Path -LiteralPath $launcherPath).Path,
        (Resolve-Path -LiteralPath $coreJavaPath).Path,
        (Resolve-Path -LiteralPath $diagnosticsJavaPath).Path
    )
    foreach ($startedProcess in $startedProcesses) {
        if ($startedProcess.HasExited) {
            continue
        }
        $actualPath = $startedProcess.MainModule.FileName
        if (-not @($expectedPaths | Where-Object {
                    [string]::Equals(
                        $_, $actualPath,
                        [System.StringComparison]::OrdinalIgnoreCase
                    )
                }).Count) {
            throw "終了対象プロセスがテスト対象と一致しません: $actualPath"
        }
        Stop-Process -Id $startedProcess.Id -Force
        $startedProcess.WaitForExit(10000) | Out-Null
    }
    foreach ($ownedProcess in @($process, $setupProcess, $guiProcess)) {
        if ($null -ne $ownedProcess) {
            $ownedProcess.Dispose()
        }
    }
    if (Test-Path -LiteralPath $systemStatePath) {
        & powershell.exe `
            -NoProfile `
            -NonInteractive `
            -ExecutionPolicy Bypass `
            -File $setupScriptPath `
            -Action Rollback `
            -StatePath $systemStatePath
        if ($LASTEXITCODE -ne 0) {
            throw "隔離初回セットアップの復元に失敗しました (ExitCode: $LASTEXITCODE)"
        }
    }
    if (Test-Path -LiteralPath $configPath) {
        Remove-Item -LiteralPath $configPath -Force
    }
    foreach ($createdPath in @($guiPropertiesPath, $setupStatePath)) {
        if (Test-Path -LiteralPath $createdPath) {
            Remove-Item -LiteralPath $createdPath -Force
        }
    }
    $generatedFiles = @(
        Get-ChildItem -LiteralPath $certificateDirectory -File `
            -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notin $initialCertificateFiles }
    )
    foreach ($generatedFile in $generatedFiles) {
        Remove-Item -LiteralPath $generatedFile.FullName -Force
    }
    $osStateAfter = Get-OsIntegrationState
    if ($osStateAfter -ne $osStateBefore) {
        throw '隔離スモークテストの前後で証明書ストアまたはプロキシー設定が変化しました'
    }
    Write-Output 'PASS 証明書ストア・Windowsプロキシー設定の不変性'
    if (Test-Path -LiteralPath $dataRoot) {
        Remove-Item -LiteralPath $dataRoot -Recurse -Force
    }
    if ($testSucceeded -and -not $KeepLogs -and
            (Test-Path -LiteralPath $logRoot)) {
        Remove-Item -LiteralPath $logRoot -Recurse -Force
    }
}
