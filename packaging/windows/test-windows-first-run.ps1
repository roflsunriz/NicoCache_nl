[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$AppImagePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:GITHUB_ACTIONS -ne 'true') {
    throw '初回Windows連携試験は一時GitHub Actionsランナー以外では実行できません'
}

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
$setupScript = Join-Path $appDirectory 'setup\windows\first-run-setup.ps1'
$launcher = Join-Path $appImage 'NicoCache_nl.exe'
$configPath = Join-Path $appDirectory 'config.properties'
$guiPropertiesPath = Join-Path $appDirectory 'NicoCacheGUI.property'
$completionStatePath =
    Join-Path $appDirectory 'data\first-run-setup.properties'
$certificateDirectory = Join-Path $appDirectory 'certs'
$certificatePath = Join-Path $certificateDirectory 'ca.cer'
$certificateTargetsPath = Join-Path $appDirectory 'certificate-targets.txt'
$statePath = Join-Path $appDirectory 'data\setup-system-state.json'
$runRegistryPath =
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValueName = 'NicoCache_nl'

foreach ($requiredPath in @(
        $setupScript,
        $launcher,
        $certificateTargetsPath
    )) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "初回Windows連携試験に必要なファイルがありません: $requiredPath"
    }
}
if (Test-Path -LiteralPath $statePath) {
    throw "既存状態を上書きしないため試験を中止します: $statePath"
}

function Get-IntegrationState {
    $proxyPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $proxy = Get-ItemProperty -LiteralPath $proxyPath -ErrorAction SilentlyContinue
    $proxyState = [ordered]@{}
    foreach ($name in @('AutoConfigURL', 'AutoDetect', 'ProxyEnable',
            'ProxyServer', 'ProxyOverride')) {
        $property = if ($proxy) { $proxy.PSObject.Properties[$name] } else { $null }
        $proxyState[$name] = if ($property) { $property.Value } else { $null }
    }
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
        CertificateThumbprints = @(
            Get-ChildItem -LiteralPath 'Cert:\CurrentUser\Root' |
                Select-Object -ExpandProperty Thumbprint |
                Sort-Object
        )
    } | ConvertTo-Json -Depth 5 -Compress
}

$stateBefore = Get-IntegrationState
$initialCertificateFiles = @(
    Get-ChildItem -LiteralPath $certificateDirectory -File `
        -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
)
$applied = $false

try {
    $certificateTargets = @(
        Get-Content -LiteralPath $certificateTargetsPath |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and -not $_.StartsWith('#') }
    )
    if ($certificateTargets.Count -eq 0) {
        throw '証明書対象一覧が空です'
    }
    $setupProcess = Start-Process -FilePath $launcher `
        -ArgumentList @(
            '--setup',
            '--headless',
            '--https=true',
            '--trust-certificate=true',
            '--proxy=true',
            '--autostart=true'
        ) `
        -WorkingDirectory $appDirectory `
        -Wait `
        -PassThru
    if ($setupProcess.ExitCode -ne 0) {
        throw "ヘッドレス初回セットアップに失敗しました (ExitCode: $($setupProcess.ExitCode))"
    }
    $applied = $true
    if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) {
        throw "生成したCA証明書がありません: $certificatePath"
    }
    $actualTargets = @(
        Get-Content -LiteralPath (
            Join-Path $certificateDirectory 'site.targets'
        ) |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    if (Compare-Object $certificateTargets $actualTargets) {
        throw '生成したサイト証明書の対象が配布一覧と一致しません'
    }
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $certificatePath
    )

    foreach ($createdPath in @(
            $configPath,
            $guiPropertiesPath,
            $completionStatePath
        )) {
        if (-not (Test-Path -LiteralPath $createdPath -PathType Leaf)) {
            throw "ヘッドレス初回セットアップの生成物がありません: $createdPath"
        }
    }

    $savedState = Get-Content -Raw -LiteralPath $statePath -Encoding UTF8 |
        ConvertFrom-Json
    if ($savedState.Status -ne 'Applied') {
        throw "Windows連携状態がAppliedではありません: $($savedState.Status)"
    }
    $proxy = Get-ItemProperty -LiteralPath (
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    )
    if ($proxy.AutoConfigURL -ne 'http://localhost:8080/proxy.pac') {
        throw 'Windows自動プロキシーURLが適用されていません'
    }
    $runValue = (Get-ItemProperty -LiteralPath $runRegistryPath `
            -Name $runValueName).$runValueName
    if ($runValue -notmatch [regex]::Escape($launcher)) {
        throw 'ログオン時起動へ製品ランチャーが登録されていません'
    }
    if (-not (Test-Path -LiteralPath (
            "Cert:\CurrentUser\Root\$($certificate.Thumbprint)"
        ))) {
        throw '現在ユーザーのルート証明書ストアへCAが登録されていません'
    }
    Write-Output 'PASS 単一EXEのヘッドレス初回セットアップとWindows連携の適用'
} finally {
    if ($applied -or (Test-Path -LiteralPath $statePath)) {
        & powershell.exe `
            -NoProfile `
            -NonInteractive `
            -ExecutionPolicy Bypass `
            -File $setupScript `
            -Action Rollback `
            -StatePath $statePath
        if ($LASTEXITCODE -ne 0) {
            throw "初回Windows連携の復元に失敗しました (ExitCode: $LASTEXITCODE)"
        }
    }
}

$stateAfter = Get-IntegrationState
if ($stateAfter -ne $stateBefore) {
    throw '初回Windows連携試験の前後でOS設定が一致しません'
}
if (Test-Path -LiteralPath $statePath) {
    Remove-Item -LiteralPath $statePath -Force
}
foreach ($createdPath in @(
        $configPath,
        $guiPropertiesPath,
        $completionStatePath,
        (Join-Path $appDirectory 'proxy.pac')
    )) {
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
Write-Output 'PASS 初回Windows連携の完全復元'
