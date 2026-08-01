[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$AppImagePath,

    [ValidateRange(5, 120)]
    [int]$StartupTimeoutSeconds = 30,

    [switch]$KeepLogs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $root '.test-work')).
    TrimEnd([System.IO.Path]::DirectorySeparatorChar)
$appImage = (Resolve-Path -LiteralPath $AppImagePath).Path
if (-not $appImage.StartsWith(
        $testRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "実環境の誤操作を防ぐため.test-work外のイメージは検証できません: $appImage"
}
$appDirectory = $appImage
$internalAppDirectory = Join-Path $appImage 'app'
$launcherPath = Join-Path $appImage 'NicoCache_nl.exe'
$separateHeadlessLauncherPath = Join-Path $appImage 'NicoCache_nl-Headless.exe'
$dataRoot = Join-Path $testRoot 'app-image-user-data'
$configPath = Join-Path $appDirectory 'config.properties'
$guiPropertiesPath = Join-Path $dataRoot 'NicoCacheGUI.property'
$setupStatePath = Join-Path $dataRoot 'data\first-run-setup.properties'
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
        (Join-Path $internalAppDirectory 'NicoCache_nl.jar'),
        (Join-Path $internalAppDirectory 'NicoCacheCA.jar'),
        (Join-Path $internalAppDirectory 'NicoCacheLauncher.jar'),
        $certificateTargetsPath,
        (Join-Path $internalAppDirectory 'lib\bcprov.jar'),
        (Join-Path $internalAppDirectory 'lib\bcpkix.jar'),
        (Join-Path $internalAppDirectory 'lib\bcutil.jar'),
        (Join-Path $appDirectory 'setup\windows\first-run-setup.ps1'),
        (Join-Path $appDirectory 'defaults\00_NicoCache.properties'),
        (Join-Path $appDirectory 'data\cors\99_sample.conf'),
        (Join-Path $appDirectory 'data\tlsclient\cacerts2'),
        (Join-Path $appDirectory 'local\mime.types.default'),
        (Join-Path $appDirectory 'documents\tls.md'),
        (Join-Path $appDirectory 'tools\cmaf-to-mp4\nico-cmaf-to-mp4.jar'),
        (Join-Path $appDirectory 'tools\cmaf-to-mp4\README.md'),
        (Join-Path $internalAppDirectory 'development\build-javac.ps1'),
        (Join-Path $internalAppDirectory 'development\src\dareka\NLMain.java'),
        (Join-Path $internalAppDirectory 'development\tests\functional\FunctionalTestMain.java')
    )) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "アプリイメージに必要なファイルがありません: $requiredPath"
    }
}
Add-Type -AssemblyName System.Drawing
$launcherIcon = [System.Drawing.Icon]::ExtractAssociatedIcon($launcherPath)
try {
    if ($null -eq $launcherIcon -or $launcherIcon.Width -lt 16) {
        throw "本体ランチャーに独自アイコンがありません: $launcherPath"
    }
} finally {
    if ($launcherIcon) { $launcherIcon.Dispose() }
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
foreach ($userOnlyDirectory in @('cache', 'certs', 'cvcache', 'thcache')) {
    if (Test-Path -LiteralPath (
            Join-Path $appDirectory $userOnlyDirectory
        )) {
        throw "ユーザー専用ディレクトリがアプリ側にあります: $userOnlyDirectory"
    }
}
$jarPath = Join-Path $internalAppDirectory 'NicoCache_nl.jar'
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
$launcherJarPath = Join-Path $internalAppDirectory 'NicoCacheLauncher.jar'
$launcherArchive = [System.IO.Compression.ZipFile]::OpenRead($launcherJarPath)
try {
    $launcherEntries = @($launcherArchive.Entries |
        Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'nicocache/launcher/LauncherMain.class',
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
$rootLaunchers = @(
    Get-ChildItem -LiteralPath $appImage -File -Filter '*.exe' |
        Select-Object -ExpandProperty Name
)
if ($rootLaunchers.Count -ne 1 -or $rootLaunchers[0] -ne 'NicoCache_nl.exe') {
    throw "アプリのランチャーが1本ではありません: $($rootLaunchers -join ', ')"
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
                    $_.Path -and [string]::Equals(
                        $_.Path,
                        $launcherPath,
                        [System.StringComparison]::OrdinalIgnoreCase
                    )
                } catch {
                    $false
                }
            }
    )
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
$knownProcessIds = @(Get-ProductProcesses | Select-Object -ExpandProperty Id)
$testSucceeded = $false
try {
    $setupProcess = Start-Process -FilePath $launcherPath `
        -ArgumentList @(
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
    $diagnosticOutput = & $launcherPath '--headless' '--check-data-root' 2>&1
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
    Write-Output 'PASS 単一EXEによるOSへ登録しないヘッドレス初回セットアップ'
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

    $process = Start-Process -FilePath $launcherPath `
        -ArgumentList '--headless' `
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

    Write-Output 'PASS 異なる作業ディレクトリからuserDataRootを解決して起動'
    Write-Output "PASS 単一製品ランチャーの内部ヘッドレス起動"
    Write-Output "PASS HTTPループバック応答 (port=$listenPort)"
    $testSucceeded = $true
} finally {
    $expectedPath = (Resolve-Path -LiteralPath $launcherPath).Path
    $startedProcesses = @(
        Get-ProductProcesses |
            Where-Object { $_.Id -notin $knownProcessIds }
    )
    foreach ($startedProcess in $startedProcesses) {
        if ($startedProcess.HasExited) {
            continue
        }
        $actualPath = $startedProcess.MainModule.FileName
        if (-not [string]::Equals(
                $expectedPath,
                $actualPath,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "終了対象プロセスがランチャーと一致しません: $actualPath"
        }
        Stop-Process -Id $startedProcess.Id -Force
        $startedProcess.WaitForExit(10000) | Out-Null
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
