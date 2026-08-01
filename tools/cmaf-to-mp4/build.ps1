[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$sourceRoot = Join-Path $root "src\main\java"
$resourceRoot = Join-Path $root "src\main\resources"
$buildRoot = Join-Path $root "build"
$classesRoot = Join-Path $buildRoot "classes"
$distRoot = Join-Path $root "dist"
$jarPath = Join-Path $distRoot "nico-cmaf-to-mp4.jar"

$javac = (Get-Command javac -ErrorAction Stop).Source
$jar = (Get-Command jar -ErrorAction Stop).Source
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter "*.java" |
    ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) {
    throw "Javaソースが見つかりません: $sourceRoot"
}

if (Test-Path -LiteralPath $classesRoot) {
    Remove-Item -LiteralPath $classesRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null
New-Item -ItemType Directory -Path $distRoot -Force | Out-Null

& $javac --release 11 -encoding UTF-8 -Xlint:all -d $classesRoot $sources
if ($LASTEXITCODE -ne 0) {
    throw "CMAF/Domand変換アプリのコンパイルに失敗しました"
}
if (Test-Path -LiteralPath $resourceRoot) {
    Copy-Item -Path (Join-Path $resourceRoot "*") -Destination $classesRoot -Recurse -Force
}

& $jar --create --file $jarPath --main-class nicocache.cmaftomp4.Main -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    throw "CMAF/Domand変換アプリのJAR作成に失敗しました"
}
Write-Output "作成しました: $jarPath"
