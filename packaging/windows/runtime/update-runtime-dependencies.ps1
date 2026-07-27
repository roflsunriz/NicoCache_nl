[CmdletBinding()]
param(
    [ValidateSet('Check', 'Update')]
    [string]$Mode = 'Check',
    [string[]]$Id,
    [string]$ApplicationRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [switch]$NonInteractive
)

$engine = Join-Path $ApplicationRoot 'extensions\update-runtime-dependencies.ps1'
if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) {
    throw "依存関係更新エンジンが見つかりません: $engine"
}

& $engine -Mode $Mode -Id $Id -ApplicationRoot $ApplicationRoot -NonInteractive:$NonInteractive
exit $LASTEXITCODE
