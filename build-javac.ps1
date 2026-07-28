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

function Get-JavacCandidates {
    $paths = [System.Collections.Generic.List[string]]::new()

    if ($env:JAVA_HOME) {
        $paths.Add((Join-Path $env:JAVA_HOME "bin\javac.exe"))
        $paths.Add((Join-Path $env:JAVA_HOME "bin\javac"))
    }

    Get-Command javac -All -ErrorAction SilentlyContinue |
        ForEach-Object { $paths.Add($_.Source) }

    $seen = @{}
    foreach ($path in $paths) {
        if (-not $path -or $seen.ContainsKey($path)) { continue }
        $seen[$path] = $true
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }

        try {
            $versionOutput = (& $path --version 2>&1 | Select-Object -First 1).ToString()
            if ($versionOutput -match 'javac\s+(\d+)(?:\.\d+)?') {
                [pscustomobject]@{
                    Major = [int]$Matches[1]
                    Path = (Resolve-Path -LiteralPath $path).Path
                }
            }
        } catch {
            # 実行できない候補は対応版として扱わない。
        }
    }
}

$javacCandidates = @(Get-JavacCandidates | Where-Object { $_.Major -in @(17, 21, 25) } |
    Sort-Object Major -Descending -Unique)
if (-not $javacCandidates) {
    throw "対応する javac (17、21、25) が見つかりません"
}

$selectedJavac = if ($PSBoundParameters.ContainsKey("JavaVersion")) {
    $javacCandidates | Where-Object Major -eq $JavaVersion | Select-Object -First 1
} else {
    $javacCandidates | Select-Object -First 1
}
if (-not $selectedJavac) {
    $available = ($javacCandidates | ForEach-Object Major) -join ", "
    throw "Java $JavaVersion の javac が見つかりません。利用可能: $available"
}

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
