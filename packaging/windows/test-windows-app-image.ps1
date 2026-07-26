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
$appDirectory = Join-Path $appImage 'app'
$launcherPath = Join-Path $appImage 'NicoCache_nl.exe'
$separateHeadlessLauncherPath = Join-Path $appImage 'NicoCache_nl-Headless.exe'
$certificateLauncherPath = Join-Path $appImage 'NicoCacheCA.exe'
$configPath = Join-Path $appDirectory 'config.properties'
$certificateDirectory = Join-Path $appDirectory 'certs'
$logRoot = Join-Path $appImage 'smoke-test-logs'
$stdoutPath = Join-Path $logRoot 'stdout.log'
$stderrPath = Join-Path $logRoot 'stderr.log'
$certificateStdoutPath = Join-Path $logRoot 'certificate-stdout.log'
$certificateStderrPath = Join-Path $logRoot 'certificate-stderr.log'

foreach ($requiredPath in @(
        $launcherPath,
        $certificateLauncherPath,
        (Join-Path $appDirectory 'NicoCache_nl.jar'),
        (Join-Path $appDirectory 'NicoCacheCA.jar'),
        (Join-Path $appDirectory 'lib\bcprov.jar'),
        (Join-Path $appDirectory 'lib\bcpkix.jar'),
        (Join-Path $appDirectory 'lib\bcutil.jar'),
        (Join-Path $appDirectory 'defaults\00_NicoCache.properties'),
        (Join-Path $appDirectory 'local\mime.types.default')
    )) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "アプリイメージに必要なファイルがありません: $requiredPath"
    }
}
if (Test-Path -LiteralPath $separateHeadlessLauncherPath) {
    throw "GUI用とヘッドレス用の製品ランチャーが分離されています: $separateHeadlessLauncherPath"
}
if (Test-Path -LiteralPath $configPath) {
    throw "既存設定を上書きしないためテストを中止します: $configPath"
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

New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
@(
    '# NicoCache_nl パッケージ隔離スモークテスト用設定'
    "listenPort=$listenPort"
    'proxyHost='
    'allowFrom=local'
    'localFileServer=true'
    'enableMitm=false'
    'title=false'
    ''
) | Set-Content -LiteralPath $configPath -Encoding utf8

$process = $null
$testSucceeded = $false
try {
    $certificateProcess = Start-Process -FilePath $certificateLauncherPath `
        -ArgumentList 'localhost' `
        -WorkingDirectory $appDirectory `
        -RedirectStandardOutput $certificateStdoutPath `
        -RedirectStandardError $certificateStderrPath `
        -Wait `
        -PassThru
    if ($certificateProcess.ExitCode -ne 0) {
        throw "隔離証明書生成に失敗しました (ExitCode: $($certificateProcess.ExitCode))"
    }
    foreach ($generatedCertificate in @('ca.cer', 'ca.jks', 'ca.pem',
            'site.cer', 'site.jks', 'site.targets')) {
        $generatedPath = Join-Path $certificateDirectory $generatedCertificate
        if (-not (Test-Path -LiteralPath $generatedPath -PathType Leaf)) {
            throw "隔離証明書生成物がありません: $generatedPath"
        }
    }
    Write-Output 'PASS OSへ登録しない隔離証明書生成'

    $process = Start-Process -FilePath $launcherPath `
        -ArgumentList '--headless' `
        -WorkingDirectory $appDirectory `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $response = $null
    do {
        if ($process.HasExited) {
            throw "単一ランチャーが起動直後に終了しました (ExitCode: $($process.ExitCode))"
        }
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$listenPort/" `
                -UseBasicParsing -TimeoutSec 2
        } catch {
            Start-Sleep -Milliseconds 250
        }
    } until ($response -or [DateTime]::UtcNow -ge $deadline)

    if (-not $response) {
        throw "単一ランチャーが${StartupTimeoutSeconds}秒以内に応答しませんでした"
    }
    if ($response.StatusCode -ne 200) {
        throw "予期しないHTTPステータスです: $($response.StatusCode)"
    }
    if ($response.Content -notmatch 'NicoCache_nl version') {
        throw 'ルート応答にNicoCache_nlのバージョン文字列がありません'
    }

    Write-Output "PASS 単一製品ランチャーの内部ヘッドレス起動"
    Write-Output "PASS HTTPループバック応答 (port=$listenPort)"
    $testSucceeded = $true
} finally {
    if ($process -and -not $process.HasExited) {
        $expectedPath = (Resolve-Path -LiteralPath $launcherPath).Path
        $actualPath = $process.MainModule.FileName
        if (-not [string]::Equals(
                $expectedPath,
                $actualPath,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "終了対象プロセスがランチャーと一致しません: $actualPath"
        }
        Stop-Process -Id $process.Id -Force
        $process.WaitForExit(10000) | Out-Null
    }
    if (Test-Path -LiteralPath $configPath) {
        Remove-Item -LiteralPath $configPath -Force
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
    if ($testSucceeded -and -not $KeepLogs -and
            (Test-Path -LiteralPath $logRoot)) {
        Remove-Item -LiteralPath $logRoot -Recurse -Force
    }
}
