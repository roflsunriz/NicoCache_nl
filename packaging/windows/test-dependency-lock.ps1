#Requires -Version 7.0

[CmdletBinding()]
param(
    [string]$LockFile = (Join-Path $PSScriptRoot 'dependency-lock.psd1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$updateScript = Join-Path $PSScriptRoot 'update-dependency-lock.ps1'
. $updateScript -FunctionsOnly

function ConvertTo-MavenMetadataXml {
    param([Parameter(Mandatory)][string[]]$Versions)

    $values = $Versions | ForEach-Object { "<version>$_</version>" }
    return [xml](
        '<metadata><versioning><versions>' +
        ($values -join '') +
        '</versions></versioning></metadata>'
    )
}

$skewedMetadata = [ordered]@{
    'bcprov-jdk18on' = ConvertTo-MavenMetadataXml @('1.84', '1.85', '1.85.2')
    'bcpkix-jdk18on' = ConvertTo-MavenMetadataXml @('1.84', '1.85')
    'bcutil-jdk18on' = ConvertTo-MavenMetadataXml @('1.84', '1.85')
}
$skewedVersion = Get-LatestCommonStableVersion -MetadataByArtifact $skewedMetadata
if ($skewedVersion -ne '1.85') {
    throw "bcprov先行公開時に未公開版を選択しました: $skewedVersion"
}

$numericMetadata = [ordered]@{}
foreach ($artifactId in @('bcprov-jdk18on', 'bcpkix-jdk18on', 'bcutil-jdk18on')) {
    $numericMetadata[$artifactId] = ConvertTo-MavenMetadataXml @(
        '1.9', '1.10', '1.86', '1.86.1', '1.86.1.9', '1.86.1.10',
        '1.100', '1.101-beta1'
    )
}
$numericVersion = Get-LatestCommonStableVersion -MetadataByArtifact $numericMetadata
if ($numericVersion -ne '1.100') {
    throw "Bouncy Castle版を数値順に比較できませんでした: $numericVersion"
}
foreach ($metadata in $numericMetadata.Values) {
    $versionNode = $metadata.CreateElement('version')
    $versionNode.InnerText = '2.0'
    [void]$metadata.metadata.versioning.versions.AppendChild($versionNode)
}
$majorVersion = Get-LatestCommonStableVersion -MetadataByArtifact $numericMetadata
if ($majorVersion -ne '2.0') {
    throw "Bouncy Castleの新しいメジャー版を選択できませんでした: $majorVersion"
}

$lock = Import-PowerShellDataFile -LiteralPath $LockFile
if ([string]$lock.BouncyCastleVersion -notmatch '^\d+(?:\.\d+){1,3}$') {
    throw 'BouncyCastleVersionの形式が不正です'
}
if ([string]$lock.BrotliDecoderVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw 'BrotliDecoderVersionの形式が不正です'
}
if ([string]$lock.ZstdJniVersion -notmatch '^\d+\.\d+\.\d+-\d+$') {
    throw 'ZstdJniVersionの形式が不正です'
}

$expectedNames = @('bcprov', 'bcpkix', 'bcutil', 'brotli-dec', 'zstd-jni')
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
        $expectedPath = switch ([string]$artifact.Name) {
            'brotli-dec' {
                "/maven2/org/brotli/dec/$($lock.BrotliDecoderVersion)/" +
                    "dec-$($lock.BrotliDecoderVersion).jar"
            }
            'zstd-jni' {
                "/maven2/com/github/luben/zstd-jni/$($lock.ZstdJniVersion)/" +
                    "zstd-jni-$($lock.ZstdJniVersion).jar"
            }
            default {
                $remoteFileName = "$($artifact.Name)-jdk18on-$($lock.BouncyCastleVersion).jar"
                "/maven2/org/bouncycastle/$($artifact.Name)-jdk18on/" +
                    "$($lock.BouncyCastleVersion)/$remoteFileName"
            }
        }
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

Write-Output (("dependency-lock.psd1を検証しました: Bouncy Castle {0}, " +
    "Brotli decoder {1}, zstd-jni {2}") -f $lock.BouncyCastleVersion,
    $lock.BrotliDecoderVersion, $lock.ZstdJniVersion)
