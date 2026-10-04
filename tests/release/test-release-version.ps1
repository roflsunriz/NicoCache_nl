[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$checker = Join-Path $root 'check-release-version.ps1'
$work = Join-Path $root ('.test-work/release-version/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $work 'src/dareka'),
    (Join-Path $work '.github/workflows'), (Join-Path $work 'compile/dareka'),
    (Join-Path $work 'classes') -Force | Out-Null
$version = '2.3.4'
$date = '2028-02-29'
$count = 0
function Reset-Fixture {
    "public class Main { public static final String VER_STRING = `"NicoCache_nl version $date (v$version)`"; }" |
        Set-Content -LiteralPath (Join-Path $work 'src/dareka/Main.java') -Encoding UTF8
    "## [Unreleased]`n`n## [$version] - $date`n`n## [2.3.3] - 2028-02-28" |
        Set-Content -LiteralPath (Join-Path $work 'CHANGELOG.md') -Encoding UTF8
    "  APP_VERSION: $version`n  APP_VERSION: $version" |
        Set-Content -LiteralPath (Join-Path $work '.github/workflows/unix-packages.yml') -Encoding UTF8
}
function Assert-Rejected {
    param([scriptblock]$Action, [string]$Message)
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw "Release guard accepted invalid metadata: $Message" }
    $script:count++
}
function New-TestJar {
    param([string]$ClassVersion = $version, [string]$ManifestVersion = $version,
        [string]$ManifestDate = $date, [switch]$MissingMetadata)
    $source = Join-Path $work 'compile/dareka/Main.java'
    "package dareka; public final class Main { public static final String VER_STRING = `"NicoCache_nl version $date (v$ClassVersion)`"; }" |
        Set-Content -LiteralPath $source -Encoding UTF8
    & javac --release 11 -encoding UTF-8 -Xlint:all -Werror -d (Join-Path $work 'classes') $source
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
    $manifest = "Manifest-Version: 1.0`n"
    if (-not $MissingMetadata) {
        $manifest += "Implementation-Version: $ManifestVersion`nNicoCache-Release-Date: $ManifestDate`n"
    }
    $manifest += "`n"
    $manifestPath = Join-Path $work 'manifest.mf'
    [IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
    & jar --create --file (Join-Path $work 'fixture.jar') --manifest $manifestPath -C (Join-Path $work 'classes') dareka
    if ($LASTEXITCODE -ne 0) { throw 'Fixture packaging failed' }
}
try {
    Reset-Fixture
    & $checker -RepositoryRoot $work -ReleaseTag "v$version" | Out-Null
    $count++
    & $checker -RepositoryRoot $work | Out-Null # 未リリース開発でも自動増分しない。
    $count++
    Assert-Rejected { & $checker -RepositoryRoot $work -ReleaseTag 'v2.3.5' } 'stale release version'
    foreach ($case in @(
        @{File='src/dareka/Main.java'; Old=$date; New='2028-02-28'},
        @{File='src/dareka/Main.java'; Old=$date; New='2028-02-30'},
        @{File='src/dareka/Main.java'; Old=$version; New='2.3.3'},
        @{File='CHANGELOG.md'; Old=$version; New='2.3.5'},
        @{File='.github/workflows/unix-packages.yml'; Old=$version; New='2.3.3'}
    )) {
        Reset-Fixture
        $file = Join-Path $work $case.File
        (Get-Content -Raw -LiteralPath $file -Encoding UTF8).Replace($case.Old, $case.New) |
            Set-Content -LiteralPath $file -Encoding UTF8
        Assert-Rejected { & $checker -RepositoryRoot $work } $case.File
    }
    Reset-Fixture
    New-TestJar
    & $checker -RepositoryRoot $work -ReleaseTag "v$version" -JarPath (Join-Path $work 'fixture.jar') | Out-Null
    $count++
    New-TestJar -ManifestVersion '2.3.3'
    Assert-Rejected { & $checker -RepositoryRoot $work -JarPath (Join-Path $work 'fixture.jar') } 'manifest version'
    New-TestJar -ManifestDate '2028-02-28'
    Assert-Rejected { & $checker -RepositoryRoot $work -JarPath (Join-Path $work 'fixture.jar') } 'manifest date'
    New-TestJar -MissingMetadata
    Assert-Rejected { & $checker -RepositoryRoot $work -JarPath (Join-Path $work 'fixture.jar') } 'missing manifest metadata'
    New-TestJar -ClassVersion '2.3.3'
    Assert-Rejected { & $checker -RepositoryRoot $work -JarPath (Join-Path $work 'fixture.jar') } 'stale class with forged matching manifest'
    Write-Output "Release version regression tests passed: $count"
} finally {
    $resolved = (Resolve-Path -LiteralPath $work).Path
    if (-not $resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe release-version fixture cleanup path'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
