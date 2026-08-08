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

function Invoke-DependencyDownload {
    param(
        [Parameter(Mandatory)][uri]$Uri,
        [Parameter(Mandatory)][string]$OutFile
    )

    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Uri -OutFile $OutFile `
                -MaximumRedirection 0 -UseBasicParsing
            return
        }
        catch {
            $response = $_.Exception.Response
            $statusCode = if ($response -and $response.StatusCode) {
                [int]$response.StatusCode
            } else { $null }
            $retryable = $null -eq $statusCode -or
                $statusCode -in @(408, 429, 500, 502, 503, 504)
            if (-not $retryable -or $attempt -eq 5) { throw }
            $delaySeconds = [Math]::Min(
                60, 5 * [Math]::Pow(2, $attempt - 1)
            )
            Write-Warning (
                "依存成果物の取得を再試行します " +
                "($attempt/5, ${delaySeconds}秒後): $Uri"
            )
            Start-Sleep -Seconds $delaySeconds
        }
    }
}

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
        Invoke-DependencyDownload -Uri $uri -OutFile $partial
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
