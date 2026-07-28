#Requires -Version 7.0

[CmdletBinding()]
param(
    [string]$LockFile = (Join-Path $PSScriptRoot 'dependency-lock.psd1'),

    [string]$UpdateScript = (Join-Path $PSScriptRoot 'update-dependency-lock.ps1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedLockFile = (Resolve-Path -LiteralPath $LockFile).Path
$lockHashBefore = (Get-FileHash -LiteralPath $resolvedLockFile -Algorithm SHA256).Hash
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) (
    'nicocache-dependency-update-test-' + [guid]::NewGuid().ToString('N')
)
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null

try {
    $reportFile = Join-Path $temporaryDirectory 'dependency-update-report.md'
    $result = & $UpdateScript `
        -Mode Check `
        -LockFile $resolvedLockFile `
        -ReportFile $reportFile

    if (-not (Test-Path -LiteralPath $reportFile -PathType Leaf)) {
        throw "依存更新レポートが生成されませんでした: $reportFile"
    }
    if ([string]$result.OldVersion -notmatch '^\d+\.\d+$' -or
        [string]$result.NewVersion -notmatch '^\d+\.\d+$') {
        throw '依存更新結果の版形式が不正です'
    }

    $report = Get-Content -Raw -LiteralPath $reportFile
    if ($report.Contains('$(')) {
        throw '依存更新レポートに未展開のPowerShell式が残っています'
    }
    if (-not $report.Contains("- 旧版: $($result.OldVersion)") -or
        -not $report.Contains("- 新版: $($result.NewVersion)")) {
        throw '依存更新レポートの版情報が実行結果と一致しません'
    }

    foreach ($artifactName in @('bcprov', 'bcpkix', 'bcutil')) {
        $escapedName = [regex]::Escape($artifactName)
        $artifactRowPattern = (
            "(?m)^\| $escapedName \| https://repo\.maven\.apache\.org/" +
            "maven2/org/bouncycastle/$escapedName-jdk18on/\d+\.\d+/" +
            "$escapedName-jdk18on-\d+\.\d+\.jar \| \x60[0-9a-f]{64}\x60 \| " +
            "[1-9]\d* \|\r?$"
        )
        if ($report -notmatch $artifactRowPattern) {
            throw "依存更新レポートの成果物行が不正です: $artifactName"
        }
    }

    $lockHashAfter = (Get-FileHash -LiteralPath $resolvedLockFile -Algorithm SHA256).Hash
    if ($lockHashAfter -ne $lockHashBefore) {
        throw 'Checkモードが依存ロックファイルを変更しました'
    }
}
finally {
    Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output '依存ロック更新処理とレポートを検証しました'
