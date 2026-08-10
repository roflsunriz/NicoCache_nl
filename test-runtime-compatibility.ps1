[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Prepare', 'Run')]
    [string]$Mode,
    [string]$LibraryDirectory = (Join-Path $PSScriptRoot 'lib'),
    [string]$ArtifactDirectory = (
        Join-Path (Join-Path $PSScriptRoot '.test-work') 'runtime-compatibility/artifact'
    ),
    [string]$JavaHome,
    [ValidateSet(17, 21, 25)]
    [int]$ExpectedMajor,
    [string]$RunId = ([Guid]::NewGuid().ToString('N')),
    [switch]$KeepWorkDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path

function Remove-SafeDirectory {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (-not $resolved.StartsWith(
            $root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないテスト作業パスです: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Invoke-Java {
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
}

$artifactRoot = [System.IO.Path]::GetFullPath($ArtifactDirectory, $root)
$appClasses = Join-Path $artifactRoot 'app-classes'
$launcherClasses = Join-Path $artifactRoot 'launcher-classes'
$fixtureClasses = Join-Path $artifactRoot 'fixture-classes'
$codecDirectory = Join-Path $artifactRoot 'codecs'

if ($Mode -eq 'Prepare') {
    Remove-SafeDirectory -Path $artifactRoot
    New-Item -ItemType Directory -Path (
        $appClasses, $launcherClasses, $fixtureClasses, $codecDirectory
    ) | Out-Null

    $codecJars = @('brotli-dec.jar', 'zstd-jni.jar') | ForEach-Object {
        $path = Join-Path $LibraryDirectory $_
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "HTTP圧縮展開ライブラリがありません: $path"
        }
        $target = Join-Path $codecDirectory $_
        Copy-Item -LiteralPath $path -Destination $target -Force
        $target
    }
    $codecClasspath = $codecJars -join [System.IO.Path]::PathSeparator

    $productSources = Get-ChildItem -LiteralPath (Join-Path $root 'src/dareka') `
        -Recurse -File -Filter '*.java' |
        Where-Object { $_.Name -ne 'package-info.java' } |
        Select-Object -ExpandProperty FullName
    $testSources = @(
        Get-ChildItem -LiteralPath (Join-Path $root 'tests/functional') `
            -File -Filter '*.java'
        Get-ChildItem -LiteralPath (Join-Path $root 'tests/setup') `
            -File -Filter '*.java'
        Get-ChildItem -LiteralPath (Join-Path $root 'tests/runtime') `
            -File -Filter '*.java'
    ) | Select-Object -ExpandProperty FullName
    Invoke-Java -Executable 'javac' -Arguments (@(
        '--release', '11', '--add-modules', 'jdk.httpserver',
        '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
        '-classpath', $codecClasspath, '-d', $appClasses
    ) + $productSources + $testSources) `
        -FailureMessage '実行時互換性テスト本体のコンパイルに失敗しました'

    $launcherSources = Get-ChildItem -LiteralPath (
        Join-Path $root 'tools/nicocache-launcher/src/main/java'
    ) -Recurse -File -Filter '*.java' | Select-Object -ExpandProperty FullName
    $launcherTestSources = Get-ChildItem -LiteralPath (
        Join-Path $root 'tests/launcher'
    ) -File -Filter '*.java' | Select-Object -ExpandProperty FullName
    Invoke-Java -Executable 'javac' -Arguments (@(
        '--release', '11', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
        '-d', $launcherClasses
    ) + $launcherSources + $launcherTestSources) `
        -FailureMessage 'ランチャー互換性テストのコンパイルに失敗しました'

    $resourceRoot = (Resolve-Path -LiteralPath (
        Join-Path $root 'tools/nicocache-launcher/src/main/resources'
    )).Path
    foreach ($resource in Get-ChildItem -LiteralPath $resourceRoot -Recurse -File) {
        $relative = $resource.FullName.Substring($resourceRoot.Length).TrimStart('\', '/')
        $target = Join-Path $launcherClasses $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force |
            Out-Null
        Copy-Item -LiteralPath $resource.FullName -Destination $target -Force
    }

    $fixtureSources = Get-ChildItem -LiteralPath (
        Join-Path $root 'tests/functional/fixtures'
    ) -File -Filter '*.java' | Select-Object -ExpandProperty FullName
    Invoke-Java -Executable 'javac' -Arguments (@(
        '--release', '11', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
        '-classpath', $appClasses, '-d', $fixtureClasses
    ) + $fixtureSources) `
        -FailureMessage '機能テスト用Extensionのコンパイルに失敗しました'

    Write-Output "実行時互換性テスト成果物を準備しました: $artifactRoot"
    return
}

if (-not $PSBoundParameters.ContainsKey('ExpectedMajor')) {
    throw 'Runモードでは-ExpectedMajorが必要です'
}
if (-not (Test-Path -LiteralPath $appClasses -PathType Container)) {
    throw "Prepare済みのテスト成果物がありません: $artifactRoot"
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaExecutable = (Get-Command java -CommandType Application |
        Select-Object -First 1).Source
} else {
    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $javaExecutable = Join-Path $JavaHome "bin/$javaName"
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        throw "Java実行ファイルがありません: $javaExecutable"
    }
}

$runRoot = Join-Path (Join-Path $root '.test-work/runtime-compatibility/runs') $RunId
Remove-SafeDirectory -Path $runRoot
$functionalSandbox = Join-Path $runRoot 'functional'
$setupSandbox = Join-Path $runRoot 'setup'
$setupPreview = Join-Path $runRoot 'preview'
New-Item -ItemType Directory -Path (
    $functionalSandbox, $setupSandbox, $setupPreview
) | Out-Null
Copy-Item -Path (Join-Path $fixtureClasses '*') `
    -Destination $functionalSandbox -Recurse -Force

$codecJars = Get-ChildItem -LiteralPath $codecDirectory -File -Filter '*.jar' |
    Select-Object -ExpandProperty FullName
$runtimeClasspath = (@($appClasses) + $codecJars) -join (
    [System.IO.Path]::PathSeparator
)

try {
    Invoke-Java -Executable $javaExecutable -Arguments @(
        '-cp', $runtimeClasspath, 'runtime.RuntimeCompatibilityTest',
        $ExpectedMajor.ToString()
    ) -FailureMessage 'Java実行環境の識別テストに失敗しました'

    Invoke-Java -Executable $javaExecutable -Arguments @(
        '--enable-native-access=ALL-UNNAMED', '--add-modules', 'jdk.httpserver',
        "-Dnicocache.test.classpath=$runtimeClasspath", '-cp', $runtimeClasspath,
        'functional.FunctionalTestMain', $root, $functionalSandbox, $appClasses
    ) -FailureMessage '機能テストに失敗しました'

    $setupClasspath = (@(
        $appClasses
        (Join-Path $root 'src')
    ) + $codecJars) -join [System.IO.Path]::PathSeparator
    Invoke-Java -Executable $javaExecutable -Arguments @(
        '-Djava.awt.headless=true', '-cp', $setupClasspath,
        'dareka.FirstRunSetupTest', $root, $setupSandbox, $setupPreview
    ) -FailureMessage '初回セットアップテストに失敗しました'

    foreach ($testClass in @(
        'nicocache.launcher.LauncherOptionsTest',
        'nicocache.launcher.LauncherTaskTest',
        'nicocache.launcher.CoreProcessTest',
        'nicocache.launcher.TaskSchedulerTest',
        'nicocache.launcher.DataRootInspectorTest',
        'nicocache.launcher.LauncherSetupDialogTest'
    )) {
        Invoke-Java -Executable $javaExecutable -Arguments @(
            '-cp', $launcherClasses, $testClass
        ) -FailureMessage "ランチャーテストに失敗しました: $testClass"
    }
    Write-Output "PASS runtime compatibility: Java $ExpectedMajor / $RunId"
} finally {
    if ($KeepWorkDir) {
        Write-Output "テスト作業ディレクトリを保持しました: $runRoot"
    } else {
        Remove-SafeDirectory -Path $runRoot
    }
}
