param([switch]$KeepWorkDir)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$workRoot = Join-Path (Join-Path $root '.test-work') 'diagnostics'
$classes = Join-Path $workRoot 'classes'

if (Test-Path -LiteralPath $workRoot) {
    $resolved = (Resolve-Path -LiteralPath $workRoot).Path
    if (-not $resolved.StartsWith(
            $root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でない診断テスト作業パスです: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null

try {
    $sources = @(
        Get-ChildItem -LiteralPath (
            Join-Path $root 'tools/nicocache-diagnostics/src/main/java'
        ) -Recurse -File -Filter '*.java' | Select-Object -ExpandProperty FullName
        Get-ChildItem -LiteralPath (Join-Path $root 'tests/diagnostics') `
            -File -Filter '*.java' | Select-Object -ExpandProperty FullName
    )
    & javac --release 11 -encoding UTF-8 -Xlint:all -Werror `
        -d $classes $sources
    if ($LASTEXITCODE -ne 0) {
        throw '診断アプリまたはテストのコンパイルに失敗しました'
    }
    $resourceRoot = Join-Path $root `
        'tools/nicocache-diagnostics/src/main/resources'
    foreach ($resource in Get-ChildItem -LiteralPath $resourceRoot -Recurse -File) {
        $relative = $resource.FullName.Substring($resourceRoot.Length).TrimStart('\', '/')
        $target = Join-Path $classes $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) `
            -Force | Out-Null
        Copy-Item -LiteralPath $resource.FullName -Destination $target -Force
    }
    foreach ($testClass in @(
            'nicocache.diagnostics.RedactorTest',
            'nicocache.diagnostics.HeartbeatEvaluatorTest',
            'nicocache.diagnostics.HtmlReportWriterTest'
        )) {
        & java -cp $classes $testClass
        if ($LASTEXITCODE -ne 0) {
            throw "診断アプリのテストに失敗しました: $testClass"
        }
    }
    $guiArguments = @()
    if ($KeepWorkDir) {
        $guiArguments += (Join-Path $workRoot 'preview\diagnostics-window.png')
    }
    & java -cp $classes nicocache.diagnostics.DiagnosticsGuiTest @guiArguments
    if ($LASTEXITCODE -ne 0) {
        throw '診断アプリのGUIテストに失敗しました'
    }
} finally {
    if ($KeepWorkDir) {
        Write-Output "診断テスト作業ディレクトリを保持しました: $workRoot"
    } elseif (Test-Path -LiteralPath $workRoot) {
        $resolved = (Resolve-Path -LiteralPath $workRoot).Path
        if ($resolved.StartsWith(
                $root + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
