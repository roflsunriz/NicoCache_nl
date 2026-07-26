[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Apply', 'Rollback')]
    [string]$Action,

    [string]$StatePath,

    [string]$ErrorPath,
    [string]$CaCertificatePath,
    [string]$AutoConfigUrl = 'http://localhost:8080/proxy.pac',
    [string]$LauncherPath,

    [switch]$EnableCertificate,
    [switch]$EnableProxy,
    [switch]$EnableAutoStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:CurrentStage = 'Windows設定処理を初期化'
$script:RollbackFailure = $null

$proxyRegistryPath =
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$runRegistryPath =
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValueName = 'NicoCache_nl'
$proxyValueNames = @(
    'AutoConfigURL',
    'AutoDetect',
    'ProxyEnable',
    'ProxyServer',
    'ProxyOverride'
)

function Write-SetupError {
    param([Parameter(Mandatory)]$ErrorRecord)

    if ([string]::IsNullOrWhiteSpace($ErrorPath)) {
        return
    }
    try {
        $fullErrorPath = [System.IO.Path]::GetFullPath($ErrorPath)
        $errorDirectory = Split-Path -Parent $fullErrorPath
        if (-not (Test-Path -LiteralPath $errorDirectory -PathType Container)) {
            return
        }
        $details = @(
            "失敗箇所: $script:CurrentStage"
            "内容: $($ErrorRecord.Exception.Message)"
            "エラーID: $($ErrorRecord.FullyQualifiedErrorId)"
        )
        if ($script:RollbackFailure) {
            $details += "ロールバック失敗: $script:RollbackFailure"
        }
        $details | Set-Content -LiteralPath $fullErrorPath -Encoding UTF8
    } catch {
        # 診断ファイルの書き込み失敗で元のエラーを隠さない。
    }
}

trap {
    Write-SetupError -ErrorRecord $_
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

function Get-RegistryValueState {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return [ordered]@{ Exists = $false; Kind = $null; Value = $null }
    }
    $item = Get-Item -LiteralPath $Path
    if ($Name -notin $item.GetValueNames()) {
        return [ordered]@{ Exists = $false; Kind = $null; Value = $null }
    }
    return [ordered]@{
        Exists = $true
        Kind = $item.GetValueKind($Name).ToString()
        Value = $item.GetValue(
            $Name,
            $null,
            [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames
        )
    }
}

function Restore-RegistryValue {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$State
    )

    if ($State.Exists) {
        if (-not (Test-Path -LiteralPath $Path)) {
            New-Item -Path $Path -Force | Out-Null
        }
        New-ItemProperty `
            -Path $Path `
            -Name $Name `
            -PropertyType $State.Kind `
            -Value $State.Value `
            -Force | Out-Null
    } elseif (Test-Path -LiteralPath $Path) {
        Remove-ItemProperty `
            -LiteralPath $Path `
            -Name $Name `
            -ErrorAction SilentlyContinue
    }
}

function Notify-ProxyChanged {
    if (-not ('NicoCache.WinInet' -as [type])) {
        Add-Type @'
using System;
using System.Runtime.InteropServices;
namespace NicoCache {
    public static class WinInet {
        [DllImport("wininet.dll", SetLastError = true)]
        public static extern bool InternetSetOption(
            IntPtr hInternet, int option, IntPtr buffer, int length);
    }
}
'@
    }
    [NicoCache.WinInet]::InternetSetOption(
        [IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
    [NicoCache.WinInet]::InternetSetOption(
        [IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
}

function Restore-State {
    param([Parameter(Mandatory)]$State)

    $changesProperty = $State.PSObject.Properties['Changes']
    $restoreAll = $null -eq $changesProperty
    $restoreProxy = $restoreAll -or $State.Changes.Proxy
    $restoreAutoStart = $restoreAll -or $State.Changes.AutoStart
    $restoreCertificate = $restoreAll -or $State.Changes.Certificate

    if ($restoreProxy) {
        foreach ($name in $proxyValueNames) {
            Restore-RegistryValue `
                -Path $proxyRegistryPath `
                -Name $name `
                -State $State.Proxy.$name
        }
        Notify-ProxyChanged
    }
    if ($restoreAutoStart) {
        Restore-RegistryValue `
            -Path $runRegistryPath `
            -Name $runValueName `
            -State $State.AutoStart
    }
    if ($restoreCertificate -and $State.Certificate.ImportedNew -and
            $State.Certificate.Thumbprint) {
        $certificateStore =
            [System.Security.Cryptography.X509Certificates.X509Store]::new(
                [System.Security.Cryptography.X509Certificates.StoreName]::Root,
                [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser
            )
        try {
            $certificateStore.Open(
                [System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite
            )
            $certificatesToRemove = $certificateStore.Certificates.Find(
                [System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,
                $State.Certificate.Thumbprint,
                $false
            )
            foreach ($certificateToRemove in $certificatesToRemove) {
                $certificateStore.Remove($certificateToRemove)
            }
        } finally {
            $certificateStore.Close()
        }
    }
}

$script:CurrentStage = 'Windows設定の保存先を確認'
if ([string]::IsNullOrWhiteSpace($StatePath)) {
    if ($Action -ne 'Rollback') {
        throw '適用時はWindows設定の状態保存先が必要です'
    }
    $installRoot = [System.IO.Path]::GetFullPath(
        (Join-Path $PSScriptRoot '..\..')
    )
    $StatePath = Join-Path $installRoot 'data\setup-system-state.json'
}
if ($Action -eq 'Rollback' -and
        [string]::IsNullOrWhiteSpace($ErrorPath)) {
    $ErrorPath = Join-Path (
        Split-Path -Parent ([System.IO.Path]::GetFullPath($StatePath))
    ) 'uninstall-windows-error.txt'
}
$fullStatePath = [System.IO.Path]::GetFullPath($StatePath)
if ($Action -eq 'Rollback' -and
        -not (Test-Path -LiteralPath $fullStatePath -PathType Leaf)) {
    return
}
$stateDirectory = Split-Path -Parent $fullStatePath
if (-not (Test-Path -LiteralPath $stateDirectory -PathType Container)) {
    throw "状態保存先のディレクトリがありません: $stateDirectory"
}

if ($Action -eq 'Rollback') {
    $script:CurrentStage = 'Windows設定をロールバック'
    $state = Get-Content -Raw -LiteralPath $fullStatePath -Encoding UTF8 |
        ConvertFrom-Json
    Restore-State -State $state
    Remove-Item -LiteralPath $fullStatePath -Force
    return
}

if (Test-Path -LiteralPath $fullStatePath) {
    throw "Windows設定状態が既に存在します: $fullStatePath"
}

$script:CurrentStage = 'Windows設定の変更前状態を読み取り'
$proxyState = [ordered]@{}
foreach ($name in $proxyValueNames) {
    $proxyState[$name] = Get-RegistryValueState `
        -Path $proxyRegistryPath `
        -Name $name
}
$certificateThumbprint = $null
$certificateWasPresent = $false
if ($EnableCertificate) {
    $resolvedCertificate = (Resolve-Path -LiteralPath $CaCertificatePath).Path
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $resolvedCertificate
    )
    $certificateThumbprint = $certificate.Thumbprint
    $certificateWasPresent = Test-Path -LiteralPath (
        "Cert:\CurrentUser\Root\$certificateThumbprint"
    )
}

$state = [ordered]@{
    Version = 2
    Status = 'Applying'
    Changes = [ordered]@{
        Certificate = $false
        Proxy = $false
        AutoStart = $false
    }
    Proxy = $proxyState
    AutoStart = Get-RegistryValueState `
        -Path $runRegistryPath `
        -Name $runValueName
    Certificate = [ordered]@{
        Thumbprint = $certificateThumbprint
        ImportedNew = $EnableCertificate -and -not $certificateWasPresent
    }
}
$script:CurrentStage = 'Windows設定状態を保存'
$state | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath $fullStatePath -Encoding UTF8

try {
    if ($EnableCertificate -and -not $certificateWasPresent) {
        $script:CurrentStage = 'CA証明書を現在のユーザーへ登録'
        $state.Changes.Certificate = $true
        $certutilPath = Join-Path $env:SystemRoot 'System32\certutil.exe'
        if (-not (Test-Path -LiteralPath $certutilPath -PathType Leaf)) {
            throw "certutil.exeが見つかりません: $certutilPath"
        }
        & $certutilPath `
            -user `
            -f `
            -silent `
            -addstore `
            Root `
            $resolvedCertificate |
            Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "CA証明書を登録できませんでした (ExitCode: $LASTEXITCODE)"
        }
        if (-not (Test-Path -LiteralPath (
                "Cert:\CurrentUser\Root\$certificateThumbprint"
            ))) {
            throw 'CA証明書を登録できませんでした'
        }
    }

    if ($EnableProxy) {
        $script:CurrentStage = 'Windows自動プロキシーを設定'
        $state.Changes.Proxy = $true
        if (-not (Test-Path -LiteralPath $proxyRegistryPath)) {
            New-Item -Path $proxyRegistryPath -Force | Out-Null
        }
        $proxyValues = [ordered]@{
            AutoConfigURL = @{ Type = 'String'; Value = $AutoConfigUrl }
            AutoDetect = @{ Type = 'DWord'; Value = 0 }
            ProxyEnable = @{ Type = 'DWord'; Value = 0 }
            ProxyServer = @{ Type = 'String'; Value = '' }
            ProxyOverride = @{
                Type = 'String'
                Value = 'localhost;127.0.0.1;<local>'
            }
        }
        foreach ($entry in $proxyValues.GetEnumerator()) {
            New-ItemProperty `
                -Path $proxyRegistryPath `
                -Name $entry.Key `
                -PropertyType $entry.Value.Type `
                -Value $entry.Value.Value `
                -Force | Out-Null
        }
        $script:CurrentStage = 'Windowsプロキシー変更を通知'
        Notify-ProxyChanged
    }

    if ($EnableAutoStart) {
        $script:CurrentStage = 'ログオン時自動起動の実行ファイルを確認'
        $resolvedLauncher = (Resolve-Path -LiteralPath $LauncherPath).Path
        $script:CurrentStage = 'ログオン時自動起動を登録'
        $state.Changes.AutoStart = $true
        if (-not (Test-Path -LiteralPath $runRegistryPath)) {
            New-Item -Path $runRegistryPath -Force | Out-Null
        }
        New-ItemProperty `
            -Path $runRegistryPath `
            -Name $runValueName `
            -PropertyType String `
            -Value "`"$resolvedLauncher`"" `
            -Force | Out-Null
    }

    $script:CurrentStage = 'Windows設定状態を確定'
    $state.Status = 'Applied'
    $state | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $fullStatePath -Encoding UTF8
} catch {
    $originalError = $_
    $failureStage = $script:CurrentStage
    $rollbackState = $state | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $rollbackCompleted = $false
    try {
        Restore-State -State $rollbackState
        $rollbackCompleted = $true
    } catch {
        $script:RollbackFailure = $_.Exception.Message
    }
    if ($rollbackCompleted) {
        $state.Changes.Certificate = $false
        $state.Changes.Proxy = $false
        $state.Changes.AutoStart = $false
    }
    $state.Status = 'RolledBackAfterFailure'
    try {
        $state | ConvertTo-Json -Depth 6 |
            Set-Content -LiteralPath $fullStatePath -Encoding UTF8
    } catch {
        if (-not $script:RollbackFailure) {
            $script:RollbackFailure = $_.Exception.Message
        }
    }
    $script:CurrentStage = $failureStage
    throw $originalError
}
