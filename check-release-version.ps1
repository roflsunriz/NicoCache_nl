[CmdletBinding()]
param(
    [string]$RepositoryRoot = $PSScriptRoot,
    [string]$ReleaseTag,
    [string]$JarPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$source = Get-Content -Raw -LiteralPath (Join-Path $root 'src/dareka/Main.java') -Encoding UTF8
$pattern = 'VER_STRING\s*=\s*"NicoCache_nl version (?<date>\d{4}-\d{2}-\d{2}) \(v(?<version>\d+\.\d+\.\d+)\)"'
$versions = [regex]::Matches($source, $pattern)
if ($versions.Count -ne 1) { throw 'Main.VER_STRINGの版・日付を一意に取得できません' }
$version = $versions[0].Groups['version'].Value
$date = $versions[0].Groups['date'].Value
[datetime]::ParseExact($date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) | Out-Null

$changelog = Get-Content -Raw -LiteralPath (Join-Path $root 'CHANGELOG.md') -Encoding UTF8
$releases = [regex]::Matches($changelog, '(?m)^## \[(?<version>\d+\.\d+\.\d+)\] - (?<date>\d{4}-\d{2}-\d{2})\r?$')
if ($releases.Count -eq 0 -or $releases[0].Groups['version'].Value -ne $version -or
        $releases[0].Groups['date'].Value -ne $date) {
    throw 'Main.VER_STRINGとCHANGELOGの最新リリース版・日付が一致しません'
}
if (@($releases | Where-Object { $_.Groups['version'].Value -eq $version }).Count -ne 1) {
    throw 'CHANGELOGの対象リリース版が重複しています'
}
if ($ReleaseTag -and $ReleaseTag -cne "v$version") {
    throw "リリースタグとMain.VER_STRINGが一致しません: $ReleaseTag / v$version"
}
$workflow = Get-Content -Raw -LiteralPath (Join-Path $root '.github/workflows/unix-packages.yml') -Encoding UTF8
$packageVersions = [regex]::Matches($workflow, '(?m)^\s+APP_VERSION:\s*(?<version>[^\s]+)\s*$')
if ($packageVersions.Count -eq 0 -or @($packageVersions | Where-Object {
            $_.Groups['version'].Value -ne $version
        }).Count -ne 0) {
    throw 'Unix配布検証のAPP_VERSIONとMain.VER_STRINGが一致しません'
}

if ($JarPath) {
    $jar = (Resolve-Path -LiteralPath $JarPath).Path
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { throw '生成JARのmanifestがありません' }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    foreach ($attribute in @{
            'Implementation-Version' = $version
            'NicoCache-Release-Date' = $date
        }.GetEnumerator()) {
        $match = [regex]::Match($manifest, '(?m)^' + [regex]::Escape($attribute.Key) + ': (?<value>[^\r\n]+)\r?$')
        if (-not $match.Success -or $match.Groups['value'].Value -ne $attribute.Value) {
            throw "生成JARのmanifestがソースと一致しません: $($attribute.Key)"
        }
    }
    # Mainの初期化・プロキシ起動を避け、実際のclass定数をJDK標準ツールで読む。
    $javap = @(Get-Command javap -CommandType Application -ErrorAction Stop)[0].Source
    $constants = @(& $javap -classpath $jar -constants dareka.Main 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or -not $constants.Contains("VER_STRING = `"NicoCache_nl version $date (v$version)`";")) {
        throw '生成JARのMain.VER_STRINGがソースと一致しません'
    }
}
Write-Output "Release metadata validated: v$version ($date)"
