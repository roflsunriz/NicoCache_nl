param(
    [switch]$KeepWorkDir,
    [string]$LibraryDirectory = (Join-Path $PSScriptRoot 'lib')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$workRoot = Join-Path (Join-Path $root '.test-work') 'e2e'
$classes = Join-Path $workRoot 'classes'
$httpSandbox = Join-Path $workRoot 'http'
$guiSandbox = Join-Path $workRoot 'gui'
$preview = Join-Path $guiSandbox 'preview'
$coreJar = Join-Path $workRoot 'NicoCache_nl-e2e-core.jar'
$testJar = Join-Path $workRoot 'NicoCacheLauncher-e2e.jar'
$codecJars = @('brotli-dec.jar', 'zstd-jni.jar') | ForEach-Object {
    $path = Join-Path $LibraryDirectory $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "HTTP圧縮展開ライブラリがありません: $path"
    }
    (Resolve-Path -LiteralPath $path).Path
}
$codecClasspath = $codecJars -join [System.IO.Path]::PathSeparator

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
    $launcherTestSources = Get-ChildItem -LiteralPath (
        Join-Path (Join-Path $root 'tests') 'launcher'
    ) -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName
    $launcherSourceRoot = (Resolve-Path -LiteralPath (
        Join-Path $root 'tools/nicocache-launcher/src/main/java'
    )).Path
    $launcherSources = Get-ChildItem -LiteralPath $launcherSourceRoot `
        -Recurse -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName

    & javac --release 11 --add-modules jdk.httpserver -encoding UTF-8 `
        -Xlint:all -Werror -classpath $codecClasspath -d $classes `
        $productSources $testSources $launcherTestSources $launcherSources
    if ($LASTEXITCODE -ne 0) {
        throw '本体またはE2Eテストのコンパイルに失敗しました'
    }

    Copy-Item -LiteralPath (
        Join-Path (Join-Path (Join-Path $root 'src') 'dareka') `
            'GUILauncherIcon.gif'
    ) -Destination (Join-Path (Join-Path $classes 'dareka') `
        'GUILauncherIcon.gif')

    $launcherResourceRoot = (Resolve-Path -LiteralPath (
        Join-Path $root 'tools/nicocache-launcher/src/main/resources'
    )).Path
    foreach ($resource in (Get-ChildItem -LiteralPath $launcherResourceRoot `
            -Recurse -File)) {
        $relative = $resource.FullName.Substring(
            $launcherResourceRoot.Length).TrimStart('\', '/')
        $target = Join-Path $classes $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) `
            -Force | Out-Null
        Copy-Item -LiteralPath $resource.FullName -Destination $target -Force
    }

    & jar cfm $coreJar (Join-Path $root 'manifest-nl.mf') `
        -C $classes dareka
    if ($LASTEXITCODE -ne 0) {
        throw 'E2E用本体JARの作成に失敗しました'
    }
    $workLibrary = Join-Path $workRoot 'lib'
    New-Item -ItemType Directory -Path $workLibrary -Force | Out-Null
    foreach ($codecJar in $codecJars) {
        Copy-Item -LiteralPath $codecJar -Destination $workLibrary -Force
    }
    & jar cfe $testJar nicocache.launcher.LauncherMain `
        -C $classes nicocache
    if ($LASTEXITCODE -ne 0) {
        throw 'E2E用起動管理JARの作成に失敗しました'
    }

    $runtimeClasspath = $classes + [System.IO.Path]::PathSeparator + $codecClasspath
    & java --add-modules jdk.httpserver -cp $runtimeClasspath `
        e2e.EndToEndTestMain $root $httpSandbox $testJar $coreJar
    if ($LASTEXITCODE -ne 0) {
        throw '実JAR E2Eテストに失敗しました'
    }

    & java -cp $runtimeClasspath nicocache.launcher.LauncherTaskTest
    if ($LASTEXITCODE -ne 0) {
        throw '起動管理タスクの回帰テストに失敗しました'
    }

    & java -cp $runtimeClasspath nicocache.launcher.LauncherOptionsTest
    if ($LASTEXITCODE -ne 0) {
        throw '起動管理CLIオプションの回帰テストに失敗しました'
    }

    & java -cp $runtimeClasspath nicocache.launcher.CoreProcessTest
    if ($LASTEXITCODE -ne 0) {
        throw '本体起動モードの回帰テストに失敗しました'
    }

    & java -cp $runtimeClasspath nicocache.launcher.TaskSchedulerTest
    if ($LASTEXITCODE -ne 0) {
        throw 'タスクスケジューラーの単体・結合契約テストに失敗しました'
    }

    & java -cp $runtimeClasspath nicocache.launcher.DataRootInspectorTest
    if ($LASTEXITCODE -ne 0) {
        throw 'ユーザーデータルート診断テストに失敗しました'
    }

    & java '-Djava.awt.headless=false' -cp $runtimeClasspath `
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
