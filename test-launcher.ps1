param(
    [switch]$KeepWorkDir
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$workRoot = Join-Path (Join-Path $root '.test-work') 'launcher'
$classes = Join-Path $workRoot 'classes'

if (Test-Path -LiteralPath $workRoot) {
    $resolvedWork = (Resolve-Path -LiteralPath $workRoot).Path
    if (-not $resolvedWork.StartsWith(
            $root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないランチャーテスト作業パスです: $resolvedWork"
    }
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force
}
New-Item -ItemType Directory -Path $classes | Out-Null

try {
    $launcherSources = Get-ChildItem -LiteralPath (
        Join-Path $root 'tools/nicocache-launcher/src/main/java'
    ) -Recurse -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName
    $testSources = Get-ChildItem -LiteralPath (
        Join-Path $root 'tests/launcher'
    ) -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName

    & javac --release 11 -encoding UTF-8 -Xlint:all -Werror `
        -d $classes $launcherSources $testSources
    if ($LASTEXITCODE -ne 0) {
        throw '起動管理アプリまたはテストのコンパイルに失敗しました'
    }

    $resourceRoot = (Resolve-Path -LiteralPath (
        Join-Path $root 'tools/nicocache-launcher/src/main/resources'
    )).Path
    foreach ($resource in (Get-ChildItem -LiteralPath $resourceRoot `
            -Recurse -File)) {
        $relative = $resource.FullName.Substring($resourceRoot.Length).
            TrimStart('\', '/')
        $target = Join-Path $classes $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) `
            -Force | Out-Null
        Copy-Item -LiteralPath $resource.FullName -Destination $target -Force
    }

    foreach ($testClass in @(
            'nicocache.launcher.LauncherOptionsTest',
            'nicocache.launcher.LauncherControlTest',
            'nicocache.launcher.LauncherTaskTest',
            'nicocache.launcher.LauncherLifecycleTest',
            'nicocache.launcher.CoreProcessTest',
            'nicocache.launcher.DiagnosticsProcessTest',
            'nicocache.launcher.TaskSchedulerTest',
            'nicocache.launcher.DataRootInspectorTest',
            'nicocache.launcher.LauncherSetupDialogTest'
        )) {
        & java -cp $classes $testClass
        if ($LASTEXITCODE -ne 0) {
            throw "起動管理アプリのテストに失敗しました: $testClass"
        }
    }
} finally {
    if ($KeepWorkDir) {
        Write-Output "ランチャーテスト作業ディレクトリを保持しました: $workRoot"
    } elseif (Test-Path -LiteralPath $workRoot) {
        $resolvedWork = (Resolve-Path -LiteralPath $workRoot).Path
        if ($resolvedWork.StartsWith(
                $root + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedWork -Recurse -Force
        }
    }
}
