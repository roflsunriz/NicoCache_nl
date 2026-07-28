[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$AppImagePath,

    [Parameter(Mandatory)]
    [string]$ZipPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $root '.test-work')).
    TrimEnd([System.IO.Path]::DirectorySeparatorChar)
$appImage = (Resolve-Path -LiteralPath $AppImagePath).Path
$zip = (Resolve-Path -LiteralPath $ZipPath).Path
foreach ($path in @($appImage, $zip)) {
    if (-not $path.StartsWith(
            $testRoot + [System.IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "実環境の誤操作を防ぐため.test-work外の成果物は検証できません: $path"
    }
}

$extractRoot = Join-Path $testRoot 'package-parity\zip'
if (Test-Path -LiteralPath $extractRoot) {
    Remove-Item -LiteralPath $extractRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $extractRoot | Out-Null
Expand-Archive -LiteralPath $zip -DestinationPath $extractRoot -Force

function Get-RelativeFiles {
    param([Parameter(Mandatory)][string]$BasePath)

    $base = (Resolve-Path -LiteralPath $BasePath).Path
    return @(Get-ChildItem -LiteralPath $base -Recurse -File |
        ForEach-Object {
            [PSCustomObject]@{
                RelativePath = $_.FullName.Substring($base.Length + 1)
                Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        })
}

$appFiles = @(Get-RelativeFiles -BasePath $appImage)
$zipFiles = @(Get-RelativeFiles -BasePath $extractRoot)
$appByPath = @{}
$zipByPath = @{}
foreach ($file in $appFiles) { $appByPath[$file.RelativePath] = $file.Hash }
foreach ($file in $zipFiles) { $zipByPath[$file.RelativePath] = $file.Hash }

$missing = @($appByPath.Keys | Where-Object { -not $zipByPath.ContainsKey($_) })
$unexpected = @($zipByPath.Keys | Where-Object { -not $appByPath.ContainsKey($_) })
$different = @($appByPath.Keys | Where-Object {
    $zipByPath.ContainsKey($_) -and $zipByPath[$_] -ne $appByPath[$_]
})
if ($missing.Count -gt 0 -or $unexpected.Count -gt 0 -or $different.Count -gt 0) {
    throw (
        "ZIPとアプリイメージの内容が一致しません。" +
        " missing=$($missing.Count), unexpected=$($unexpected.Count)," +
        " different=$($different.Count)"
    )
}

Remove-Item -LiteralPath $extractRoot -Recurse -Force
Write-Output "PASS ZIPとMSI共通アプリイメージの内容一致 ($($appFiles.Count) files)"
