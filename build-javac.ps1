param(
    [ValidateSet(17, 21, 25)]
    [int]$JavaVersion = 25,
    [string]$LibraryDirectory,
    [string]$OutputDirectory,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
. (Join-Path $root "java-tool-selection.ps1")

$selectionParameters = @{
    Candidates = @(Get-JavaToolCandidates "javac")
    JavaVersion = $JavaVersion
}
$selectedJavac = Select-JavaToolCandidate @selectionParameters
$javaSelection = @{
    Candidates = @(Get-JavaToolCandidates "java")
    JavaVersion = $JavaVersion
}
$selectedJava = Select-JavaToolCandidate @javaSelection
$javaBin = Split-Path -Parent $selectedJavac.Path
$jarPath = Join-Path $javaBin "jar.exe"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    $jarPath = Join-Path $javaBin "jar"
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "JDKのjarコマンドが見つかりません: $javaBin"
}

$buildSourceRoot = Join-Path $root "tools\nicocache-build\src\main\java"
$buildJar = Join-Path $root "NicoCacheBuild.jar"
$bootstrapRoot = Join-Path $root ".build\nicocache-build-bootstrap"
$bootstrapClasses = Join-Path $bootstrapRoot "classes"
$sources = @(Get-ChildItem -LiteralPath $buildSourceRoot -Recurse -File -Filter "*.java" |
    Select-Object -ExpandProperty FullName)
if ($sources.Count -eq 0) {
    throw "NicoCacheBuildのソースが見つかりません: $buildSourceRoot"
}
$needsBootstrap = -not (Test-Path -LiteralPath $buildJar -PathType Leaf)
if (-not $needsBootstrap) {
    $buildJarItem = Get-Item -LiteralPath $buildJar
    $needsBootstrap = @($sources | ForEach-Object {
            (Get-Item -LiteralPath $_).LastWriteTimeUtc -gt $buildJarItem.LastWriteTimeUtc
        } | Where-Object { $_ }).Count -gt 0
}
if ($needsBootstrap) {
    New-Item -ItemType Directory -Path $bootstrapClasses -Force | Out-Null
    & $selectedJavac.Path --release 11 -encoding UTF-8 -Xlint:all -Werror `
        -d $bootstrapClasses $sources
    if ($LASTEXITCODE -ne 0) {
        throw "NicoCacheBuildのブートストラップコンパイルに失敗しました"
    }
    & $jarPath --create --file $buildJar --main-class nicocache.build.BuildMain `
        -C $bootstrapClasses nicocache
    if ($LASTEXITCODE -ne 0) {
        throw "NicoCacheBuild.jarの作成に失敗しました"
    }
}

Write-Host "Eclipse Temurin JDK $($selectedJava.Major) のNicoCacheBuildを使用します: $buildJar"
$builderArguments = @("-jar", $buildJar, "--root=$root")
if ($PSBoundParameters.ContainsKey("LibraryDirectory")) {
    $libraryPath = (Resolve-Path -LiteralPath $LibraryDirectory).Path
    $builderArguments += "--library-dir=$libraryPath"
}
if ($PSBoundParameters.ContainsKey("OutputDirectory")) {
    $outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
    $builderArguments += "--output-dir=$outputPath"
}
if ($Clean) {
    $builderArguments += "--clean"
}
& $selectedJava.Path @builderArguments
if ($LASTEXITCODE -ne 0) {
    throw "NicoCacheBuildの実行に失敗しました"
}
