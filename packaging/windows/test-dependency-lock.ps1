#Requires -Version 7.0

[CmdletBinding()]
param(
    [string]$LockFile = (Join-Path $PSScriptRoot 'dependency-lock.psd1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$lock = Import-PowerShellDataFile -LiteralPath $LockFile
if ([string]$lock.BouncyCastleVersion -notmatch '^\d+\.\d+$') {
    throw 'BouncyCastleVersionの形式が不正です'
}

$expectedNames = @('bcprov', 'bcpkix', 'bcutil')
$actualNames = @($lock.Artifacts | ForEach-Object { [string]$_.Name })
$expectedNameSet = (@($expectedNames | Sort-Object) -join ',')
$actualNameSet = (@($actualNames | Sort-Object) -join ',')
if ($actualNameSet -ne $expectedNameSet) {
    throw "依存ファイルの集合が不正です: $($actualNames -join ', ')"
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ('nicocache-lock-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    foreach ($artifact in $lock.Artifacts) {
        $uri = [uri]$artifact.Url
        if ($uri.Scheme -ne 'https' -or $uri.Host -ne 'repo.maven.apache.org') {
            throw "公式Maven Central以外のURLを拒否しました: $uri"
        }
        if (-not $uri.IsDefaultPort -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
            throw "公式Maven Centralの標準URL以外を拒否しました: $uri"
        }
        $remoteFileName = "$($artifact.Name)-jdk18on-$($lock.BouncyCastleVersion).jar"
        $expectedPath = (
            "/maven2/org/bouncycastle/$($artifact.Name)-jdk18on/" +
            "$($lock.BouncyCastleVersion)/$remoteFileName"
        )
        if (-not $uri.AbsolutePath.Equals($expectedPath, [StringComparison]::Ordinal)) {
            throw "版・座標・URLが整合していません: $uri"
        }
        if ([string]$artifact.FileName -ne "$($artifact.Name).jar") {
            throw "配置ファイル名が不正です: $($artifact.FileName)"
        }
        if ([string]$artifact.Sha256 -notmatch '^[0-9a-f]{64}$') {
            throw "SHA-256の形式が不正です: $($artifact.Name)"
        }

        $destination = Join-Path $temp ([string]$artifact.FileName)
        Invoke-WebRequest -Uri $uri -OutFile $destination -MaximumRedirection 0 -UseBasicParsing
        $hash = Get-FileHash -LiteralPath $destination -Algorithm SHA256
        $actualHash = $hash.Hash.ToLowerInvariant()
        if ($actualHash -ne [string]$artifact.Sha256) {
            throw "SHA-256が一致しません: $($artifact.Name)"
        }
    }
}
finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "dependency-lock.psd1を検証しました: Bouncy Castle $($lock.BouncyCastleVersion)"
