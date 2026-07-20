$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
Push-Location -LiteralPath $root

try {
    $sources = Get-ChildItem -LiteralPath ".\src\dareka" -Recurse -File -Filter "*.java" |
        Where-Object { $_.Name -ne "package-info.java" } |
        ForEach-Object { $_.FullName }
    & javac --release 11 -encoding UTF-8 -Xlint:-options -d ".\src" $sources
    if ($LASTEXITCODE -ne 0) {
        throw "本体のコンパイルに失敗しました"
    }

    & jar cfm "NicoCache_nl.jar" ".\manifest-nl.mf" -C ".\src" dareka -C ".\src" native
    if ($LASTEXITCODE -ne 0) {
        throw "NicoCache_nl.jar の作成に失敗しました"
    }
} finally {
    Pop-Location
}
