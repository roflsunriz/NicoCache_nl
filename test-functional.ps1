param(
    [switch]$KeepWorkDir
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$workRoot = Join-Path (Join-Path $root ".test-work") "functional"
$classes = Join-Path $workRoot "classes"
$sampleClasses = Join-Path $workRoot "sample-classes"
$sandbox = Join-Path $workRoot "sandbox"
$testJar = Join-Path $workRoot "NicoCache_nl-test.jar"

if (Test-Path -LiteralPath $workRoot) {
    $resolvedWork = (Resolve-Path -LiteralPath $workRoot).Path
    if (-not $resolvedWork.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "安全でないテスト作業パスです: $resolvedWork"
    }
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force
}

New-Item -ItemType Directory -Path $classes, $sampleClasses, $sandbox | Out-Null

try {
    $productSources = Get-ChildItem -LiteralPath (Join-Path (Join-Path $root "src") "dareka") `
        -Recurse -File -Filter "*.java" |
        Where-Object { $_.Name -ne "package-info.java" } |
        Select-Object -ExpandProperty FullName
    $testSources = Get-ChildItem -LiteralPath (Join-Path (Join-Path $root "tests") "functional") `
        -File -Filter "*.java" |
        Select-Object -ExpandProperty FullName

    & javac --release 11 --add-modules jdk.httpserver -encoding UTF-8 -Xlint:all `
        -d $classes $productSources $testSources
    if ($LASTEXITCODE -ne 0) {
        throw "本体または機能テストのコンパイルに失敗しました"
    }

    & jar cf $testJar -C $classes dareka
    if ($LASTEXITCODE -ne 0) {
        throw "ABI 検証用 JAR の作成に失敗しました"
    }

    $compatRoot = Join-Path (Join-Path $root "tests") "compat"
    $baselinePath = Join-Path $compatRoot "extension-api.sha256"
    if (-not (Test-Path -LiteralPath $baselinePath)) {
        throw "ABI 基準ファイルがありません: $baselinePath"
    }
    $baselineHash = Get-Content -LiteralPath $baselinePath |
        Where-Object { $_ -notmatch '^\s*#' -and $_ -match '\S' } |
        Select-Object -Last 1
    $actualHash = & (Join-Path $compatRoot "get-extension-api-hash.ps1") `
        -JarPath $testJar
    if ($LASTEXITCODE -ne 0 -or $actualHash -ne $baselineHash) {
        throw "Extension ABI が変化しました。expected=$baselineHash actual=$actualHash"
    }
    Write-Output "PASS Extension public/protected ABI ($actualHash)"

    $baselineApiPath = Join-Path $compatRoot "extension-api.txt"
    $allowedRemovalPath = Join-Path $compatRoot "allowed-api-removals.txt"
    $baselineApi = Get-Content -LiteralPath $baselineApiPath |
        Where-Object { $_ -match '\S' -and $_ -notmatch '^\s*#' }
    $actualApi = & (Join-Path $compatRoot "get-extension-api.ps1") `
        -JarPath $testJar
    if ($LASTEXITCODE -ne 0) {
        throw "Extension ABI 一覧を生成できませんでした"
    }
    $allowedRemovals = Get-Content -LiteralPath $allowedRemovalPath |
        Where-Object { $_ -match '\S' -and $_ -notmatch '^\s*#' }
    $missingApi = @($baselineApi | Where-Object { $_ -notin $actualApi })
    $addedApi = @($actualApi | Where-Object { $_ -notin $baselineApi })
    $unapprovedRemovals = @($missingApi | Where-Object { $_ -notin $allowedRemovals })
    $unusedApprovals = @($allowedRemovals | Where-Object { $_ -notin $missingApi })
    if ($addedApi.Count -gt 0 -or $unapprovedRemovals.Count -gt 0 -or
            $unusedApprovals.Count -gt 0) {
        $details = @(
            $addedApi | ForEach-Object { "追加または変更: $_" }
            $unapprovedRemovals | ForEach-Object { "未許可の削除: $_" }
            $unusedApprovals | ForEach-Object { "差分にない許可: $_" }
        ) -join [Environment]::NewLine
        throw "Extension ABI 差分が許可リストと一致しません:`n$details"
    }
    Write-Output "PASS Extension ABI entries ($($actualApi.Count) entries, $($missingApi.Count) approved removals)"

    $sampleSources = Get-ChildItem -LiteralPath (Join-Path $root "extensions") `
        -File -Filter "*Sample.java" |
        Select-Object -ExpandProperty FullName
    & javac --release 11 -encoding UTF-8 -Xlint:all -classpath $classes `
        -d $sampleClasses $sampleSources
    if ($LASTEXITCODE -ne 0) {
        throw "同梱 Extension サンプルのコンパイルに失敗しました"
    }
    Write-Output "PASS bundled Extension sample compilation"

    $fixtureSource = Get-ChildItem -LiteralPath (
        Join-Path (Join-Path (Join-Path $root "tests") "functional") "fixtures"
    ) `
        -File -Filter "*.java" |
        Select-Object -ExpandProperty FullName
    & javac --release 11 -encoding UTF-8 -Xlint:all -classpath $classes `
        -d $sandbox $fixtureSource
    if ($LASTEXITCODE -ne 0) {
        throw "機能テスト用 Extension のコンパイルに失敗しました"
    }

    & java --add-modules jdk.httpserver -cp $classes functional.FunctionalTestMain `
        $root $sandbox $classes
    if ($LASTEXITCODE -ne 0) {
        throw "機能テストに失敗しました"
    }
} finally {
    if ($KeepWorkDir) {
        Write-Output "テスト作業ディレクトリを保持しました: $workRoot"
    } elseif (Test-Path -LiteralPath $workRoot) {
        $resolvedWork = (Resolve-Path -LiteralPath $workRoot).Path
        if ($resolvedWork.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedWork -Recurse -Force
        }
    }
}
