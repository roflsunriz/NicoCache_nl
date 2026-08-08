#Requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('Check', 'Update')]
    [string]$Mode = 'Check',

    [string]$LockFile = (Join-Path $PSScriptRoot 'dependency-lock.psd1'),

    [string]$ReportFile = (Join-Path $PSScriptRoot 'dependency-update-report.md'),

    [switch]$FunctionsOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryBaseUri = [uri]'https://repo.maven.apache.org/maven2/'
$GroupPath = 'org/bouncycastle'
$ArtifactIds = [ordered]@{
    bcprov = 'bcprov-jdk18on'
    bcpkix = 'bcpkix-jdk18on'
    bcutil = 'bcutil-jdk18on'
}
$AllowedLicenseNames = @('Bouncy Castle Licence')
$AllowedLicenseHosts = @('www.bouncycastle.org', 'bouncycastle.org')

function Assert-OfficialUri {
    param([Parameter(Mandatory)][uri]$Uri)

    if ($Uri.Scheme -ne 'https') {
        throw "HTTPSではない配布元を拒否しました: $Uri"
    }
    if ($Uri.Host -ne $RepositoryBaseUri.Host) {
        throw "公式Maven Central以外の配布元を拒否しました: $Uri"
    }
    if (-not $Uri.IsDefaultPort -or $Uri.UserInfo -or $Uri.Query -or $Uri.Fragment) {
        throw "公式Maven Centralの標準URL以外を拒否しました: $Uri"
    }
    $allowedPathPrefix = "$($RepositoryBaseUri.AbsolutePath)$GroupPath/"
    if (-not $Uri.AbsolutePath.StartsWith($allowedPathPrefix, [StringComparison]::Ordinal)) {
        throw "Bouncy Castle公式座標外のパスを拒否しました: $Uri"
    }
}

function Invoke-OfficialRequest {
    param(
        [Parameter(Mandatory)][uri]$Uri,
        [string]$OutFile
    )

    Assert-OfficialUri -Uri $Uri
    $parameters = @{
        Uri = $Uri
        MaximumRedirection = 0
        UseBasicParsing = $true
    }
    if ($OutFile) {
        $parameters.OutFile = $OutFile
    }
    Invoke-WebRequest @parameters
}

function Get-LatestCommonStableVersion {
    param(
        [Parameter(Mandatory)]
        [Collections.IDictionary]$MetadataByArtifact
    )

    if ($MetadataByArtifact.Count -eq 0) {
        throw 'Bouncy CastleのMavenメタデータがありません'
    }
    $common = $null
    foreach ($entry in $MetadataByArtifact.GetEnumerator()) {
        $versions = [Collections.Generic.HashSet[string]]::new(
            [StringComparer]::Ordinal
        )
        foreach ($candidate in @(
                $entry.Value.metadata.versioning.versions.version |
                    ForEach-Object { ([string]$_).Trim() }
            )) {
            # System.Versionで数値比較できる2〜4セグメントだけを安定版とする。
            # 接尾辞付きプレリリースや未知の版表現は自動更新へ混ぜない。
            if ($candidate -match '^\d+(?:\.\d+){1,3}$') {
                [void]$versions.Add($candidate)
            }
        }
        if ($versions.Count -eq 0) {
            throw "Maven metadataに安定版がありません: $($entry.Key)"
        }
        if ($null -eq $common) {
            $common = [Collections.Generic.HashSet[string]]::new(
                $versions, [StringComparer]::Ordinal
            )
        } else {
            $common.IntersectWith($versions)
        }
    }
    if ($null -eq $common -or $common.Count -eq 0) {
        throw 'Bouncy Castle 3成果物の共通安定版がありません'
    }

    $latest = $null
    foreach ($candidate in $common) {
        if ($null -eq $latest -or
                ([version]$candidate) -gt ([version]$latest)) {
            $latest = $candidate
        }
    }
    return $latest
}

function Get-LatestStableVersion {
    $metadataByArtifact = [ordered]@{}
    foreach ($artifactId in $ArtifactIds.Values) {
        $metadataUri = [uri]::new(
            $RepositoryBaseUri,
            "$GroupPath/$artifactId/maven-metadata.xml"
        )
        $metadataByArtifact[$artifactId] = [xml](
            Invoke-OfficialRequest -Uri $metadataUri
        ).Content
    }
    return Get-LatestCommonStableVersion -MetadataByArtifact $metadataByArtifact
}

function Get-LicenseInformation {
    param([Parameter(Mandatory)][string]$Version)

    $artifactId = $ArtifactIds.bcprov
    $pomUri = [uri]::new(
        $RepositoryBaseUri,
        "$GroupPath/$artifactId/$Version/$artifactId-$Version.pom"
    )
    [xml]$pom = (Invoke-OfficialRequest -Uri $pomUri).Content
    $licenseNode = $pom.SelectSingleNode(
        "/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license'][1]"
    )
    if (-not $licenseNode) {
        throw "Bouncy Castle $Version のPOMにライセンス情報がありません"
    }

    $nameNode = $licenseNode.SelectSingleNode("*[local-name()='name']")
    $urlNode = $licenseNode.SelectSingleNode("*[local-name()='url']")
    $name = [string]$nameNode.InnerText
    $url = [uri]([string]$urlNode.InnerText)

    if ($name -notin $AllowedLicenseNames) {
        throw "想定外のライセンス名を検出しました: $name"
    }
    if ($url.Scheme -ne 'https' -or $url.Host -notin $AllowedLicenseHosts) {
        throw "想定外のライセンスURLを検出しました: $url"
    }

    return [pscustomobject]@{
        Name = $name
        Url = $url.AbsoluteUri
        Pom = $pomUri.AbsoluteUri
    }
}

function Get-ArtifactInformation {
    param([Parameter(Mandatory)][string]$Version)

    $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) (
        'nicocache-dependency-' + [guid]::NewGuid().ToString('N')
    )
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    try {
        $result = foreach ($entry in $ArtifactIds.GetEnumerator()) {
            $name = [string]$entry.Key
            $artifactId = [string]$entry.Value
            $remoteFileName = "$artifactId-$Version.jar"
            $uri = [uri]::new(
                $RepositoryBaseUri,
                "$GroupPath/$artifactId/$Version/$remoteFileName"
            )
            $temporaryFile = Join-Path $temporaryDirectory $remoteFileName
            Invoke-OfficialRequest -Uri $uri -OutFile $temporaryFile | Out-Null

            $file = Get-Item -LiteralPath $temporaryFile
            if ($file.Length -le 0) {
                throw "空の依存ファイルを拒否しました: $uri"
            }

            [pscustomobject]@{
                Name = $name
                FileName = "$name.jar"
                Url = $uri.AbsoluteUri
                Sha256 = (Get-FileHash -LiteralPath $temporaryFile -Algorithm SHA256).
                    Hash.ToLowerInvariant()
                Size = $file.Length
            }
        }
        return @($result)
    }
    finally {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Write-LockFile {
    param(
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][array]$Artifacts,
        [Parameter(Mandatory)][hashtable]$ExistingLock
    )

    $preservedArtifacts = @($ExistingLock.Artifacts | Where-Object {
        [string]$_.Name -notin @($ArtifactIds.Keys)
    })
    $allArtifacts = @($Artifacts) + $preservedArtifacts
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('@{')
    $lines.Add("    BouncyCastleVersion = '$Version'")
    $lines.Add("    BrotliDecoderVersion = '$($ExistingLock.BrotliDecoderVersion)'")
    $lines.Add("    ZstdJniVersion = '$($ExistingLock.ZstdJniVersion)'")
    $lines.Add('    Artifacts = @(')
    for ($index = 0; $index -lt $allArtifacts.Count; $index++) {
        $artifact = $allArtifacts[$index]
        $lines.Add('        @{')
        $lines.Add("            Name = '$($artifact.Name)'")
        $lines.Add("            FileName = '$($artifact.FileName)'")
        $lines.Add("            Url = '$($artifact.Url)'")
        $lines.Add("            Sha256 = '$($artifact.Sha256)'")
        $lines.Add('        }')
    }
    $lines.Add('    )')
    $lines.Add('}')
    $lines.Add('')

    $temporaryFile = "$LockFile.tmp"
    [IO.File]::WriteAllLines($temporaryFile, $lines, [Text.UTF8Encoding]::new($false))
    Import-PowerShellDataFile -LiteralPath $temporaryFile | Out-Null
    Move-Item -LiteralPath $temporaryFile -Destination $LockFile -Force
}

function Write-Report {
    param(
        [Parameter(Mandatory)][string]$CurrentVersion,
        [Parameter(Mandatory)][string]$LatestVersion,
        [Parameter(Mandatory)][bool]$Updated,
        [Parameter(Mandatory)][object]$License,
        [Parameter(Mandatory)][array]$Artifacts
    )

    $status = if ($Updated) { '更新あり' } else { '更新なし' }
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('# リポジトリ依存関係の更新レポート')
    $lines.Add('')
    $lines.Add("- 状態: $status")
    $lines.Add("- 旧版: $CurrentVersion")
    $lines.Add("- 新版: $LatestVersion")
    $lines.Add("- 配布元: $($RepositoryBaseUri.AbsoluteUri)")
    $lines.Add("- ライセンス: $($License.Name)")
    $lines.Add("- ライセンスURL: $($License.Url)")
    $lines.Add("- 検証POM: $($License.Pom)")
    $lines.Add('')
    $lines.Add('| 名前 | URL | SHA-256 | サイズ |')
    $lines.Add('|---|---|---|---:|')
    foreach ($artifact in $Artifacts) {
        $lines.Add("| $($artifact.Name) | $($artifact.Url) | ``$($artifact.Sha256)`` | $($artifact.Size) |")
    }
    $lines.Add('')
    $lines.Add('プレリリース、ダウングレード、公式座標外URL、ライセンス変更は自動更新対象外です。')
    $lines.Add('')

    $reportDirectory = Split-Path -Parent $ReportFile
    if ($reportDirectory) {
        New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    }
    [IO.File]::WriteAllLines($ReportFile, $lines, [Text.UTF8Encoding]::new($false))
}

if ($FunctionsOnly) {
    return
}

$resolvedLockFile = Resolve-Path -LiteralPath $LockFile
$LockFile = $resolvedLockFile.Path
$lock = Import-PowerShellDataFile -LiteralPath $LockFile
$currentVersion = [string]$lock.BouncyCastleVersion
if ($currentVersion -notmatch '^\d+(?:\.\d+){1,3}$') {
    throw "現在のBouncy Castle版が不正です: $currentVersion"
}

$latestVersion = Get-LatestStableVersion
if ([version]$latestVersion -lt [version]$currentVersion) {
    throw "ダウングレードを拒否しました: $currentVersion -> $latestVersion"
}

$targetVersion = if ([version]$latestVersion -gt [version]$currentVersion) {
    $latestVersion
} else {
    $currentVersion
}
$license = Get-LicenseInformation -Version $targetVersion
$artifacts = Get-ArtifactInformation -Version $targetVersion
$hasUpdate = [version]$latestVersion -gt [version]$currentVersion

if ($Mode -eq 'Update' -and $hasUpdate) {
    Write-LockFile -Version $latestVersion -Artifacts $artifacts `
        -ExistingLock $lock
}
Write-Report `
    -CurrentVersion $currentVersion `
    -LatestVersion $latestVersion `
    -Updated $hasUpdate `
    -License $license `
    -Artifacts $artifacts

if ($env:GITHUB_OUTPUT) {
    "updated=$($hasUpdate.ToString().ToLowerInvariant())" | Out-File `
        -LiteralPath $env:GITHUB_OUTPUT -Encoding utf8 -Append
    "old-version=$currentVersion" | Out-File `
        -LiteralPath $env:GITHUB_OUTPUT -Encoding utf8 -Append
    "new-version=$latestVersion" | Out-File `
        -LiteralPath $env:GITHUB_OUTPUT -Encoding utf8 -Append
    "report=$ReportFile" | Out-File `
        -LiteralPath $env:GITHUB_OUTPUT -Encoding utf8 -Append
}

[pscustomobject]@{
    Updated = $hasUpdate
    OldVersion = $currentVersion
    NewVersion = $latestVersion
    Report = $ReportFile
}
