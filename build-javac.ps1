param(
    [ValidateSet(17, 21, 25)]
    [int]$JavaVersion
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$sourceRoot = Join-Path $root "src"
$javaSourceRoot = Join-Path $sourceRoot "dareka"
$manifestPath = Join-Path $root "manifest-nl.mf"
$jarPath = Join-Path $root "NicoCache_nl.jar"
. (Join-Path $root "java-tool-selection.ps1")

$selectionParameters = @{ Candidates = @(Get-JavaToolCandidates "javac") }
if ($PSBoundParameters.ContainsKey("JavaVersion")) {
    $selectionParameters.JavaVersion = $JavaVersion
}
$selectedJavac = Select-JavaToolCandidate @selectionParameters

Write-Host "javac $($selectedJavac.Major) を使用します: $($selectedJavac.Path)"
Push-Location -LiteralPath $root

try {
    $sources = Get-ChildItem -LiteralPath $javaSourceRoot -Recurse -File -Filter "*.java" |
        Where-Object { $_.Name -ne "package-info.java" } |
        ForEach-Object { $_.FullName }
    & $selectedJavac.Path --release 11 -encoding UTF-8 -Xlint:-options -d $sourceRoot $sources
    if ($LASTEXITCODE -ne 0) {
        throw "本体のコンパイルに失敗しました"
    }

    & jar cfm $jarPath $manifestPath -C $sourceRoot dareka -C $sourceRoot native
    if ($LASTEXITCODE -ne 0) {
        throw "NicoCache_nl.jar の作成に失敗しました"
    }
} finally {
    Pop-Location
}
