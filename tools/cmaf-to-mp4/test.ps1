[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $root '..\..')).Path
. (Join-Path $repoRoot 'java-tool-selection.ps1')
& (Join-Path $root "build.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "ビルドに失敗しました"
}

$sourceRoot = Join-Path $root "src\test\java"
$classesRoot = Join-Path $root "build\classes"
$testClassesRoot = Join-Path $root "build\test-classes"
$selectedJavac = Select-JavaToolCandidate `
    -Candidates @(Get-JavaToolCandidates 'javac')
$javac = $selectedJavac.Path
$javaName = if ([System.IO.Path]::GetExtension($javac) -eq '.exe') {
    'java.exe'
} else { 'java' }
$java = Join-Path (Split-Path -Parent $javac) $javaName
Assert-TemurinJavaRuntime -JavaPath $java -JavaVersion 25 | Out-Null
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter "*.java" |
    ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) {
    throw "テストソースが見つかりません: $sourceRoot"
}
if (Test-Path -LiteralPath $testClassesRoot) {
    Remove-Item -LiteralPath $testClassesRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $testClassesRoot -Force | Out-Null
& $javac --release 11 -encoding UTF-8 -Xlint:all -cp $classesRoot -d $testClassesRoot $sources
if ($LASTEXITCODE -ne 0) {
    throw "単体テストのコンパイルに失敗しました"
}
& $java -cp ($classesRoot + ";" + $testClassesRoot) nicocache.cmaftomp4.CmafToMp4Tests
if ($LASTEXITCODE -ne 0) {
    throw "単体テストに失敗しました"
}
