param(
    [switch]$KeepWorkDir
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$testRoot = Join-Path $root '.test-work\first-run-setup'
$classes = Join-Path $testRoot 'classes'
$sandbox = Join-Path $testRoot 'sandbox'
$preview = Join-Path $testRoot 'preview'

if (Test-Path -LiteralPath $testRoot) {
    $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
    if (-not $resolvedTestRoot.StartsWith(
            $root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないテスト作業パスです: $resolvedTestRoot"
    }
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classes, $sandbox, $preview | Out-Null

try {
    $productSources = Get-ChildItem -LiteralPath (Join-Path $root 'src\dareka') `
        -Recurse -File -Filter '*.java' |
        Where-Object { $_.Name -ne 'package-info.java' } |
        Select-Object -ExpandProperty FullName
    $testSources = Get-ChildItem -LiteralPath (Join-Path $root 'tests\setup') `
        -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName

    & javac --release 11 -encoding UTF-8 '-Xlint:all,-auxiliaryclass' `
        -d $classes `
        $productSources `
        $testSources
    if ($LASTEXITCODE -ne 0) {
        throw '初回セットアップ本体またはテストのコンパイルに失敗しました'
    }

    $classpath = $classes + [System.IO.Path]::PathSeparator + (
        Join-Path $root 'src'
    )
    & java `
        '-Djava.awt.headless=true' `
        -cp $classpath `
        dareka.FirstRunSetupTest `
        $root `
        $sandbox `
        $preview
    if ($LASTEXITCODE -ne 0) {
        throw '初回セットアップテストに失敗しました'
    }
} finally {
    if ($KeepWorkDir) {
        Write-Output "テスト作業ディレクトリを保持しました: $testRoot"
    } elseif (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
        if ($resolvedTestRoot.StartsWith(
                $root + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
        }
    }
}
