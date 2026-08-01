#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$DestinationDirectory,

    [string]$LockFile = (Join-Path $PSScriptRoot 'dependency-lock.psd1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedLockFile = (Resolve-Path -LiteralPath $LockFile).Path
$lock = Import-PowerShellDataFile -LiteralPath $resolvedLockFile
$destination = [System.IO.Path]::GetFullPath($DestinationDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null

foreach ($artifact in $lock.Artifacts) {
    $uri = [uri]$artifact.Url
    if (($uri.Scheme -ne 'https') -or
        ($uri.Host -ne 'repo.maven.apache.org') -or
        (-not $uri.IsDefaultPort) -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw "公式Maven Centralの標準URL以外を拒否しました: $uri"
    }

    $fileName = [string]$artifact.FileName
    $target = Join-Path $destination $fileName
    $partial = "$target.download"
    if (Test-Path -LiteralPath $partial) {
        Remove-Item -LiteralPath $partial -Force
    }
    try {
        Invoke-WebRequest -Uri $uri -OutFile $partial -MaximumRedirection 0 `
            -UseBasicParsing
        $actualHash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne [string]$artifact.Sha256) {
            throw "依存ファイルのSHA-256が一致しません: $($artifact.Name)"
        }
        Move-Item -LiteralPath $partial -Destination $target -Force
    }
    catch {
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        throw
    }
}

Write-Output "ビルド用依存ファイルを準備しました: $destination"
