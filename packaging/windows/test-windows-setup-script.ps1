[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = Join-Path $root '.test-work\windows-setup-script'
$scriptSource = Join-Path $PSScriptRoot 'runtime\first-run-setup.ps1'
$scriptPath = Join-Path $testRoot 'first-run-setup.ps1'
$statePath = Join-Path $testRoot 'data\setup-system-state.json'
$errorPath = Join-Path $testRoot 'data\setup-windows-error.txt'
$standardOutputPath = Join-Path $testRoot 'powershell.stdout.txt'
$standardErrorPath = Join-Path $testRoot 'powershell.stderr.txt'
$missingLauncher = Join-Path $testRoot 'missing-launcher.exe'

if (-not (Test-Path -LiteralPath $scriptSource -PathType Leaf)) {
    throw "Windows設定スクリプトがありません: $scriptSource"
}
$tokens = $null
$parseErrors = $null
$scriptAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $scriptSource,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) {
    throw "Windows設定スクリプトを解析できません: $($parseErrors[0].Message)"
}
$certificateRemovals = @(
    $scriptAst.FindAll({
            param($node)

            if ($node -isnot
                    [System.Management.Automation.Language.CommandAst] -or
                    $node.GetCommandName() -ne 'Remove-Item' -or
                    $node.Extent.Text -notmatch '\$certificatePath') {
                return $false
            }
            return @(
                $node.CommandElements |
                    Where-Object {
                        $_ -is
                            [System.Management.Automation.Language.CommandParameterAst] -and
                        $_.ParameterName -eq 'Force'
                    }
            ).Count -eq 1
        }, $true)
)
if ($certificateRemovals.Count -ne 1) {
    throw '信頼済みルートCAの無人削除に必要な -Force がありません'
}
if (Test-Path -LiteralPath $testRoot) {
    $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
    if (-not $resolvedTestRoot.StartsWith(
            $root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないテスト作業パスです: $resolvedTestRoot"
    }
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
}
New-Item -ItemType Directory -Path (Split-Path -Parent $statePath) -Force |
    Out-Null
$scriptContent = [System.IO.File]::ReadAllText(
    $scriptSource,
    [System.Text.UTF8Encoding]::new($false, $true)
)
[System.IO.File]::WriteAllText(
    $scriptPath,
    $scriptContent,
    [System.Text.UTF8Encoding]::new($true)
)
$scriptBytes = [System.IO.File]::ReadAllBytes($scriptPath)
if ($scriptBytes.Length -lt 3 -or
        $scriptBytes[0] -ne 0xEF -or
        $scriptBytes[1] -ne 0xBB -or
        $scriptBytes[2] -ne 0xBF) {
    throw 'Windows PowerShell 5.1向けスクリプトにUTF-8 BOMがありません'
}

$apply = Start-Process `
    -FilePath 'powershell.exe' `
    -ArgumentList @(
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy', 'Bypass',
        '-File', $scriptPath,
        '-Action', 'Apply',
        '-StatePath', $statePath,
        '-ErrorPath', $errorPath,
        '-LauncherPath', $missingLauncher,
        '-EnableAutoStart'
    ) `
    -Wait `
    -PassThru `
    -RedirectStandardOutput $standardOutputPath `
    -RedirectStandardError $standardErrorPath

if ($apply.ExitCode -ne 1) {
    throw "意図した失敗終了になりませんでした (ExitCode: $($apply.ExitCode))"
}
if (-not (Test-Path -LiteralPath $errorPath -PathType Leaf)) {
    throw "失敗診断ファイルが作成されませんでした: $errorPath"
}
$errorText = Get-Content -Raw -LiteralPath $errorPath -Encoding UTF8
if ($errorText -notmatch '失敗箇所: ログオン時自動起動の実行ファイルを確認') {
    throw "失敗段階が診断ファイルへ記録されていません: $errorText"
}
if ($errorText -notmatch '内容:') {
    throw "失敗内容が診断ファイルへ記録されていません: $errorText"
}

$state = Get-Content -Raw -LiteralPath $statePath -Encoding UTF8 |
    ConvertFrom-Json
if ($state.Version -ne 2 -or $state.Status -ne 'RolledBackAfterFailure') {
    throw '失敗後のWindows設定状態が想定と一致しません'
}
if ($state.Changes.Certificate -or $state.Changes.Proxy -or
        $state.Changes.AutoStart) {
    throw '変更開始前の失敗なのにWindows設定が変更済みとして記録されました'
}

$rollbackErrorPath = Join-Path $testRoot 'data\rollback-error.txt'
$rollback = Start-Process `
    -FilePath 'powershell.exe' `
    -ArgumentList @(
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy', 'Bypass',
        '-File', $scriptPath,
        '-Action', 'Rollback',
        '-StatePath', $statePath,
        '-ErrorPath', $rollbackErrorPath
    ) `
    -Wait `
    -PassThru `
    -RedirectStandardOutput $standardOutputPath `
    -RedirectStandardError $standardErrorPath

if ($rollback.ExitCode -ne 0) {
    $rollbackError = if (Test-Path -LiteralPath $rollbackErrorPath) {
        Get-Content -Raw -LiteralPath $rollbackErrorPath -Encoding UTF8
    } else {
        "ExitCode: $($rollback.ExitCode)"
    }
    throw "未変更状態のロールバックに失敗しました: $rollbackError"
}
if (Test-Path -LiteralPath $statePath) {
    throw "ロールバック後も状態ファイルが残っています: $statePath"
}

$missingStatePath = Join-Path $testRoot (
    'missing-data\setup-system-state.json'
)
$missingStateRollback = Start-Process `
    -FilePath 'powershell.exe' `
    -ArgumentList @(
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy', 'Bypass',
        '-File', $scriptPath,
        '-Action', 'Rollback',
        '-StatePath', $missingStatePath
    ) `
    -Wait `
    -PassThru `
    -RedirectStandardOutput $standardOutputPath `
    -RedirectStandardError $standardErrorPath

if ($missingStateRollback.ExitCode -ne 0) {
    throw "状態保存先がないロールバックに失敗しました (ExitCode: $($missingStateRollback.ExitCode))"
}

Write-Output 'PASS Windows設定の段階別診断と信頼済みルートCAの無人ロールバック'
