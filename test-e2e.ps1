param(
    [switch]$KeepWorkDir
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$workRoot = Join-Path (Join-Path $root '.test-work') 'e2e'
$classes = Join-Path $workRoot 'classes'
$httpSandbox = Join-Path $workRoot 'http'
$guiSandbox = Join-Path $workRoot 'gui'
$preview = Join-Path $guiSandbox 'preview'
$testJar = Join-Path $workRoot 'NicoCache_nl-e2e.jar'

if (Test-Path -LiteralPath $workRoot) {
    $resolvedWork = (Resolve-Path -LiteralPath $workRoot).Path
    if (-not $resolvedWork.StartsWith(
            $root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないE2E作業パスです: $resolvedWork"
    }
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force
}
New-Item -ItemType Directory -Path `
    $classes, $httpSandbox, $guiSandbox, $preview | Out-Null

try {
    $productSources = Get-ChildItem -LiteralPath (
        Join-Path (Join-Path $root 'src') 'dareka'
    ) -Recurse -File -Filter '*.java' |
        Where-Object { $_.Name -ne 'package-info.java' } |
        Select-Object -ExpandProperty FullName
    $testSources = Get-ChildItem -LiteralPath (
        Join-Path (Join-Path $root 'tests') 'e2e'
    ) -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName

    & javac --release 11 --add-modules jdk.httpserver -encoding UTF-8 `
        '-Xlint:all,-auxiliaryclass' -d $classes `
        $productSources $testSources
    if ($LASTEXITCODE -ne 0) {
        throw '本体またはE2Eテストのコンパイルに失敗しました'
    }

    Copy-Item -LiteralPath (
        Join-Path (Join-Path (Join-Path $root 'src') 'dareka') `
            'GUILauncherIcon.gif'
    ) -Destination (Join-Path (Join-Path $classes 'dareka') `
        'GUILauncherIcon.gif')

    & jar cfm $testJar (Join-Path $root 'manifest-nl.mf') `
        -C $classes dareka
    if ($LASTEXITCODE -ne 0) {
        throw 'E2E用製品JARの作成に失敗しました'
    }

    & java --add-modules jdk.httpserver -cp $classes `
        e2e.EndToEndTestMain $root $httpSandbox $testJar
    if ($LASTEXITCODE -ne 0) {
        throw '実JAR E2Eテストに失敗しました'
    }

    & java '-Djava.awt.headless=false' -cp $classes `
        dareka.GuiEndToEndTestMain $guiSandbox $preview
    if ($LASTEXITCODE -ne 0) {
        throw 'GUI E2Eテストに失敗しました'
    }
} finally {
    if ($KeepWorkDir) {
        Write-Output "E2E作業ディレクトリを保持しました: $workRoot"
    } elseif (Test-Path -LiteralPath $workRoot) {
        $resolvedWork = (Resolve-Path -LiteralPath $workRoot).Path
        if ($resolvedWork.StartsWith(
                $root + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedWork -Recurse -Force
        }
    }
}
