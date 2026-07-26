[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$MsiPath,

    [ValidateRange(5, 120)]
    [int]$StartupTimeoutSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = Join-Path $root '.test-work'
$installRoot = Join-Path $testRoot 'windows-msi-install'
$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path

$fullTestRoot = [System.IO.Path]::GetFullPath($testRoot).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar
)
$fullInstallRoot = [System.IO.Path]::GetFullPath($installRoot)
if (-not $fullInstallRoot.StartsWith(
        $fullTestRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "安全でないMSIテスト先です: $fullInstallRoot"
}
if (Test-Path -LiteralPath $installRoot) {
    Remove-Item -LiteralPath $installRoot -Recurse -Force
}

$installArguments = @(
    '/i',
    "`"$resolvedMsi`"",
    '/qn',
    '/norestart',
    "INSTALLDIR=`"$installRoot`""
)
$installProcess = Start-Process -FilePath 'msiexec.exe' `
    -ArgumentList $installArguments -Wait -PassThru
if ($installProcess.ExitCode -ne 0) {
    throw "MSIの無人インストールに失敗しました (ExitCode: $($installProcess.ExitCode))"
}

try {
    if (-not (Test-Path -LiteralPath $installRoot -PathType Container)) {
        throw "MSIが指定先へインストールされませんでした: $installRoot"
    }
    & (Join-Path $PSScriptRoot 'test-windows-app-image.ps1') `
        -AppImagePath $installRoot `
        -StartupTimeoutSeconds $StartupTimeoutSeconds
    Write-Output 'PASS MSIの無人インストール'
} finally {
    $uninstallArguments = @(
        '/x',
        "`"$resolvedMsi`"",
        '/qn',
        '/norestart'
    )
    $uninstallProcess = Start-Process -FilePath 'msiexec.exe' `
        -ArgumentList $uninstallArguments -Wait -PassThru
    if ($uninstallProcess.ExitCode -ne 0) {
        throw "MSIの無人アンインストールに失敗しました (ExitCode: $($uninstallProcess.ExitCode))"
    }
}

if (Test-Path -LiteralPath $installRoot) {
    $remainingFiles = @(Get-ChildItem -LiteralPath $installRoot -Recurse -File)
    if ($remainingFiles.Count -gt 0) {
        $names = $remainingFiles | Select-Object -ExpandProperty FullName
        throw "MSIアンインストール後にファイルが残っています:`n$($names -join "`n")"
    }
    Remove-Item -LiteralPath $installRoot -Recurse -Force
}
Write-Output 'PASS MSIの無人アンインストール'
