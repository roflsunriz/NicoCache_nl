Set-Location $PSScriptRoot

# このスクリプトだけで、ベースソースのコンパイルからオーバーレイ適用、JAR 作成まで行う。
$baseSources = Get-ChildItem -Path ".\src\dareka" -Recurse -Filter "*.java" |
    Where-Object { $_.Name -ne "package-info.java" } |
    ForEach-Object { $_.FullName }

& javac --release 11 -encoding UTF-8 -Xlint:-options -d ".\src" $baseSources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$overlaySources = @(
    ".\ajax-rm-domand-cache-reset\DomandCVIManager.java",
    ".\ajax-rm-domand-cache-reset\CacheDirProcessor.java",
    ".\ajax-rm-domand-cache-reset\Workarounds.java",
    ".\ajax-rm-domand-cache-reset\HostportResource.java",
    ".\ajax-rm-domand-cache-reset\CmafCachingProcessor.java",
    ".\ajax-rm-domand-cache-reset\NLMain.java"
)

& javac --release 11 -encoding UTF-8 -Xlint:-options -classpath ".\src" -d ".\src" $overlaySources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$manifest = Join-Path ([System.IO.Path]::GetTempPath()) `
    ("NicoCache_nl-manifest-" + [Guid]::NewGuid().ToString("N") + ".mf")
try {
    @(
        "Manifest-Version: 1.0"
        "Main-Class: dareka.NLMain"
        "Class-Path: sqlite-jdbc.jar igo.jar library.jar"
        "Add-Opens: java.base/sun.net java.base/sun.net.www.protocol.http java.base/java.net java.base/java.lang java.base/java.lang.reflect"
        ""
    ) | Set-Content -LiteralPath $manifest -Encoding ascii

    & jar cfm "NicoCache_nl.jar" $manifest -C ".\src" dareka -C ".\src" native
    exit $LASTEXITCODE
}
finally {
    Remove-Item -LiteralPath $manifest -ErrorAction SilentlyContinue
}
