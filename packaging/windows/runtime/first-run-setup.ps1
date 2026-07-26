[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Apply', 'Rollback')]
    [string]$Action,

    [Parameter(Mandatory)]
    [string]$StatePath,

    [string]$CaCertificatePath,
    [string]$AutoConfigUrl = 'http://localhost:8080/proxy.pac',
    [string]$LauncherPath,

    [switch]$EnableCertificate,
    [switch]$EnableProxy,
    [switch]$EnableAutoStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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

    foreach ($name in $proxyValueNames) {
        Restore-RegistryValue `
            -Path $proxyRegistryPath `
            -Name $name `
            -State $State.Proxy.$name
    }
    Restore-RegistryValue `
        -Path $runRegistryPath `
        -Name $runValueName `
        -State $State.AutoStart
    if ($State.Certificate.ImportedNew -and $State.Certificate.Thumbprint) {
        $certificatePath =
            "Cert:\CurrentUser\Root\$($State.Certificate.Thumbprint)"
        Remove-Item -LiteralPath $certificatePath -ErrorAction SilentlyContinue
    }
    Notify-ProxyChanged
}

$fullStatePath = [System.IO.Path]::GetFullPath($StatePath)
$stateDirectory = Split-Path -Parent $fullStatePath
if (-not (Test-Path -LiteralPath $stateDirectory -PathType Container)) {
    throw "状態保存先のディレクトリがありません: $stateDirectory"
}

if ($Action -eq 'Rollback') {
    if (-not (Test-Path -LiteralPath $fullStatePath -PathType Leaf)) {
        return
    }
    $state = Get-Content -Raw -LiteralPath $fullStatePath -Encoding UTF8 |
        ConvertFrom-Json
    Restore-State -State $state
    Remove-Item -LiteralPath $fullStatePath -Force
    return
}

if (Test-Path -LiteralPath $fullStatePath) {
    throw "Windows設定状態が既に存在します: $fullStatePath"
}

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
    Version = 1
    Status = 'Applying'
    Proxy = $proxyState
    AutoStart = Get-RegistryValueState `
        -Path $runRegistryPath `
        -Name $runValueName
    Certificate = [ordered]@{
        Thumbprint = $certificateThumbprint
        ImportedNew = $EnableCertificate -and -not $certificateWasPresent
    }
}
$state | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath $fullStatePath -Encoding UTF8

try {
    if ($EnableCertificate -and -not $certificateWasPresent) {
        Import-Certificate `
            -FilePath $resolvedCertificate `
            -CertStoreLocation 'Cert:\CurrentUser\Root' | Out-Null
    }

    if ($EnableProxy) {
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
        Notify-ProxyChanged
    }

    if ($EnableAutoStart) {
        $resolvedLauncher = (Resolve-Path -LiteralPath $LauncherPath).Path
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

    $state.Status = 'Applied'
    $state | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $fullStatePath -Encoding UTF8
} catch {
    $rollbackState = $state | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    Restore-State -State $rollbackState
    $state.Status = 'RolledBackAfterFailure'
    $state | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $fullStatePath -Encoding UTF8
    throw
}
