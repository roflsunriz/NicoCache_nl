#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$DestinationRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$toolRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
if (-not (Test-Path -LiteralPath $DestinationRoot -PathType Container)) {
    throw "パッケージ入力先ディレクトリが見つかりません: $DestinationRoot"
}
$destinationRootPath = (Resolve-Path -LiteralPath $DestinationRoot).Path
$jarPath = Join-Path $toolRoot 'dist/nico-cmaf-to-mp4.jar'
$readmePath = Join-Path $toolRoot 'README.md'

if ($IsWindows) {
    $buildScript = Join-Path $toolRoot 'build.ps1'
    & $buildScript
} else {
    $buildScript = Join-Path $toolRoot 'build.sh'
    $shellCommand = Get-Command sh -ErrorAction Stop
    $shellPath = if ($shellCommand.Source) { $shellCommand.Source } else { $shellCommand.Path }
    & $shellPath $buildScript
}
if ($LASTEXITCODE -ne 0) {
    throw "CMAF/Domand変換アプリのパッケージ用ビルドに失敗しました (ExitCode: $LASTEXITCODE)"
}
foreach ($requiredPath in @($jarPath, $readmePath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "CMAF/Domand変換アプリのパッケージ用資材がありません: $requiredPath"
    }
}

$destination = Join-Path (Join-Path $destinationRootPath 'tools') 'cmaf-to-mp4'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $destination 'nico-cmaf-to-mp4.jar') -Force
Copy-Item -LiteralPath $readmePath -Destination (Join-Path $destination 'README.md') -Force
Write-Output "パッケージへCMAF/Domand変換アプリを追加しました: $destination"
