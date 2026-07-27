[CmdletBinding()]
param(
    [ValidateSet('Check', 'Update')]
    [string]$Mode = 'Check',
    [string[]]$Id,
    [string]$ApplicationRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [int]$JavaMajor = 0,
    [switch]$NonInteractive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$definition = Import-PowerShellDataFile -LiteralPath (Join-Path $PSScriptRoot 'runtime-dependencies.psd1')
$stateRoot = Join-Path $ApplicationRoot '.runtime-dependency-updater'
$downloadRoot = Join-Path $stateRoot 'downloads'
$stagingRoot = Join-Path $stateRoot 'staging'
$backupRoot = Join-Path $stateRoot 'backups'
$pendingPath = Join-Path $stateRoot 'pending-update.json'
$installedStatePath = Join-Path $stateRoot 'installed-versions.json'
New-Item -ItemType Directory -Force -Path $stateRoot, $downloadRoot, $stagingRoot, $backupRoot | Out-Null

function Invoke-JsonRequest([string]$Uri) {
    if (([Uri]$Uri).Scheme -ne 'https') { throw "HTTPS以外のAPIです: $Uri" }
    Invoke-RestMethod -Uri $Uri -Headers @{
        'User-Agent' = 'NicoCache_nl runtime dependency updater'
        'Accept' = 'application/vnd.github+json'
    }
}

function Invoke-TextRequest([string]$Uri) {
    if (([Uri]$Uri).Scheme -ne 'https') { throw "HTTPS以外の取得元です: $Uri" }
    (Invoke-WebRequest -Uri $Uri -UseBasicParsing -Headers @{
        'User-Agent' = 'NicoCache_nl runtime dependency updater'
    }).Content
}

function Get-Hash([string]$Path, [string]$Algorithm) {
    (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLowerInvariant()
}

function Assert-ManagedPath([string]$Path) {
    $root = [IO.Path]::GetFullPath($ApplicationRoot).TrimEnd('\', '/')
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "管理対象外のパスです: $full"
    }
    $full
}

function Get-InstalledState {
    if (-not (Test-Path -LiteralPath $installedStatePath -PathType Leaf)) { return @{} }
    $json = Get-Content -LiteralPath $installedStatePath -Raw | ConvertFrom-Json
    $table = @{}
    foreach ($property in $json.PSObject.Properties) { $table[$property.Name] = [string]$property.Value }
    $table
}

function Save-InstalledState([hashtable]$State) {
    $State | ConvertTo-Json | Set-Content -LiteralPath $installedStatePath -Encoding UTF8
}

function Get-AdoptiumPlatform {
    $os = if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [Runtime.InteropServices.OSPlatform]::Windows)) {
        'windows'
    } elseif ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [Runtime.InteropServices.OSPlatform]::Linux)) {
        'linux'
    } elseif ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [Runtime.InteropServices.OSPlatform]::OSX)) {
        'mac'
    } else {
        throw "Temurin自動更新に未対応のOSです: $([Runtime.InteropServices.RuntimeInformation]::OSDescription)"
    }

    $architecture = switch ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()) {
        'x64' { 'x64' }
        'x86' { 'x32' }
        'arm64' { 'aarch64' }
        'arm' { 'arm' }
        's390x' { 's390x' }
        'ppc64le' { 'ppc64' }
        default { throw "Temurin自動更新に未対応のCPUです: $([Runtime.InteropServices.RuntimeInformation]::OSArchitecture)" }
    }
    [pscustomobject]@{ Os=$os; Architecture=$architecture }
}

function Get-JavaExecutableName {
    if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [Runtime.InteropServices.OSPlatform]::Windows)) { 'java.exe' } else { 'java' }
}

function Get-SelectedJavaMajor($Dependency) {
    $supported = @($Dependency.SupportedLtsVersions | ForEach-Object { [int]$_ })
    $selected = if ($JavaMajor -gt 0) { $JavaMajor } else { [int]$Dependency.RecommendedLtsVersion }
    if ($selected -notin $supported) {
        throw "Java $selected はNicoCache_nlで検証済みのLTSではありません。選択可能: $($supported -join ', ')"
    }
    $selected
}

function Invoke-VersionCommand($Dependency, [string]$Executable) {
    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { return $null }
    $output = & $Executable @($Dependency.VersionArguments) 2>&1 | Out-String
    $match = [regex]::Match($output, $Dependency.VersionPattern,
        [Text.RegularExpressions.RegexOptions]::Multiline)
    if ($match.Success) { $match.Groups['version'].Value } else { $null }
}

function Get-ManagedExecutable($Dependency, [string]$ManagedRoot) {
    if ($Dependency.Id -eq 'temurin') {
        return Join-Path (Join-Path $ManagedRoot 'bin') (Get-JavaExecutableName)
    }
    Join-Path $ManagedRoot $Dependency.Executable
}

function Get-CurrentDependency($Dependency, [hashtable]$InstalledState) {
    $managedRoot = Assert-ManagedPath (Join-Path $ApplicationRoot $Dependency.ManagedPath)
    $stateVersion = $InstalledState[$Dependency.Id]
    if ($Dependency.Id -eq 'bouncycastle') {
        if ($stateVersion) { return [pscustomobject]@{ Version=$stateVersion; Managed=$true; Path=$managedRoot } }
        $lockPath = Join-Path $ApplicationRoot $Dependency.VersionSource
        if (Test-Path -LiteralPath $lockPath) {
            $lock = Import-PowerShellDataFile -LiteralPath $lockPath
            return [pscustomobject]@{ Version=$lock.BouncyCastleVersion; Managed=$true; Path=$managedRoot }
        }
        return [pscustomobject]@{ Version=$null; Managed=$true; Path=$managedRoot }
    }

    $managedExe = Get-ManagedExecutable $Dependency $managedRoot
    $version = Invoke-VersionCommand $Dependency $managedExe
    if ($version) {
        if ($stateVersion) { $version = $stateVersion }
        return [pscustomobject]@{ Version=$version; Managed=$true; Path=$managedRoot }
    }

    if ($Dependency.Id -eq 'temurin') {
        $externalExe = $null
        if ($env:JAVA_HOME) {
            $externalExe = Join-Path (Join-Path $env:JAVA_HOME 'bin') (Get-JavaExecutableName)
        }
        if (-not $externalExe -or -not (Test-Path -LiteralPath $externalExe -PathType Leaf)) {
            $command = Get-Command (Get-JavaExecutableName) -ErrorAction SilentlyContinue
            if ($command) { $externalExe = $command.Source }
        }
        if ($externalExe) {
            $version = Invoke-VersionCommand $Dependency $externalExe
            if ($version) { return [pscustomobject]@{ Version=$version; Managed=$false; Path=$externalExe } }
        }
    }

    $commandName = switch ($Dependency.Id) {
        'ffmpeg' { 'ffmpeg' }
        'ant' { 'ant' }
        '7zip' { if ($IsWindows) { '7z.exe' } else { '7z' } }
        default { $null }
    }
    if ($commandName) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) {
            return [pscustomobject]@{
                Version = Invoke-VersionCommand $Dependency $command.Source
                Managed = $false
                Path = $command.Source
            }
        }
    }
    [pscustomobject]@{ Version=$null; Managed=$true; Path=$managedRoot }
}

function New-Artifact([string]$Url, [string]$Hash, [string]$Algorithm,
        [string]$FileName, [string]$Role='payload') {
    if (-not $Url -or -not $Hash) { throw "配布URLまたはハッシュが欠落しています: $FileName" }
    [pscustomobject]@{
        url=$Url; hash=$Hash.ToLowerInvariant(); algorithm=$Algorithm
        fileName=$FileName; role=$Role
    }
}

function Resolve-AdoptiumRelease($Dependency) {
    $platform = Get-AdoptiumPlatform
    $major = Get-SelectedJavaMajor $Dependency
    $uri = "https://api.adoptium.net/v3/assets/latest/$major/hotspot" +
        "?architecture=$($platform.Architecture)&image_type=$($Dependency.ImageType)" +
        "&os=$($platform.Os)&vendor=eclipse"
    $assets = @(Invoke-JsonRequest $uri)
    if ($assets.Count -eq 0) {
        throw "Adoptium APIに $($platform.Os)/$($platform.Architecture)/Java $major LTS のランタイムがありません。"
    }
    $asset = $assets[0]
    $package = $asset.binary.package
    [pscustomobject]@{
        id=$Dependency.Id; version=[string]$asset.version.semver; archiveType='zip'
        javaMajor=$major; os=$platform.Os; architecture=$platform.Architecture
        artifacts=@(New-Artifact ([string]$package.link) ([string]$package.checksum) 'SHA256' ([string]$package.name))
    }
}

function Resolve-BtbNRelease($Dependency) {
    $release = Invoke-JsonRequest "https://api.github.com/repos/$($Dependency.Repository)/releases/tags/latest"
    $asset = @($release.assets | Where-Object { $_.name -match $Dependency.AssetPattern }) | Select-Object -First 1
    $checksums = @($release.assets | Where-Object { $_.name -eq 'checksums.sha256' }) | Select-Object -First 1
    if (-not $asset -or -not $checksums) { throw 'FFmpegの対象ZIPまたはchecksums.sha256が見つかりません。' }
    $checksumText = Invoke-TextRequest ([string]$checksums.browser_download_url)
    $escapedName = [regex]::Escape([string]$asset.name)
    $match = [regex]::Match($checksumText, "(?m)^([0-9a-fA-F]{64})\s+\*?$escapedName\s*$")
    if (-not $match.Success) { throw "FFmpegのSHA-256を取得できません: $($asset.name)" }
    [pscustomobject]@{
        id=$Dependency.Id
        version=([DateTimeOffset]$release.published_at).UtcDateTime.ToString('yyyyMMddHHmmss')
        archiveType='zip'
        artifacts=@(New-Artifact ([string]$asset.browser_download_url) $match.Groups[1].Value 'SHA256' ([string]$asset.name))
    }
}

function Resolve-MavenCentralRelease($Dependency) {
    $base = "https://repo.maven.apache.org/maven2/$($Dependency.MavenGroupPath)"
    [xml]$metadata = Invoke-TextRequest "$base/$($Dependency.MavenArtifacts[0])/maven-metadata.xml"
    $version = [string]$metadata.metadata.versioning.release
    if (-not $version) { $version = [string]$metadata.metadata.versioning.latest }
    if (-not $version) { throw 'Maven Central metadataにBouncy Castleの最新版がありません。' }
    $artifacts = @()
    foreach ($artifactId in $Dependency.MavenArtifacts) {
        $fileName = switch -Regex ($artifactId) {
            '^bcprov-' { 'bcprov.jar' }
            '^bcpkix-' { 'bcpkix.jar' }
            '^bcutil-' { 'bcutil.jar' }
            default { throw "未知のBouncy Castle artifactです: $artifactId" }
        }
        $jarUrl = "$base/$artifactId/$version/$artifactId-$version.jar"
        $sha = (Invoke-TextRequest "$jarUrl.sha256").Trim().Split()[0]
        if ($sha -notmatch '^[0-9a-fA-F]{64}$') { throw "Maven CentralのSHA-256が不正です: $artifactId" }
        $artifacts += New-Artifact $jarUrl $sha 'SHA256' $fileName
    }
    [pscustomobject]@{ id=$Dependency.Id; version=$version; archiveType='files'; artifacts=$artifacts }
}

function Resolve-ApacheAntRelease($Dependency) {
    $page = Invoke-WebRequest -Uri $Dependency.DistributionUri -UseBasicParsing
    $candidates = foreach ($link in $page.Links) {
        $href = [string]$link.href
        $match = [regex]::Match($href, '^apache-ant-(\d+\.\d+\.\d+)-bin\.zip$')
        if ($match.Success) {
            [pscustomobject]@{ Version=[version]$match.Groups[1].Value; FileName=$href }
        }
    }
    $selected = $candidates | Sort-Object Version -Descending | Select-Object -First 1
    if (-not $selected) { throw 'Apache Antの最新バイナリZIPを検出できませんでした。' }
    $url = [Uri]::new([Uri]$Dependency.DistributionUri, $selected.FileName).AbsoluteUri
    $sha = (Invoke-TextRequest "$url.sha512").Trim().Split()[0]
    if ($sha -notmatch '^[0-9a-fA-F]{128}$') { throw 'Apache AntのSHA-512が不正です。' }
    [pscustomobject]@{
        id=$Dependency.Id; version=$selected.Version.ToString(); archiveType='zip'
        artifacts=@(New-Artifact $url $sha 'SHA512' $selected.FileName)
    }
}

function Get-GitHubAssetHash($Asset) {
    $digest = [string]$Asset.digest
    if ($digest -match '^sha256:([0-9a-fA-F]{64})$') { return $matches[1] }
    throw "GitHub Release assetにSHA-256 digestがありません: $($Asset.name)"
}

function Resolve-7ZipRelease($Dependency) {
    $release = Invoke-JsonRequest "https://api.github.com/repos/$($Dependency.Repository)/releases/latest"
    $payload = @($release.assets | Where-Object { $_.name -match $Dependency.AssetPattern }) | Select-Object -First 1
    $bootstrap = @($release.assets | Where-Object { $_.name -match $Dependency.BootstrapPattern }) | Select-Object -First 1
    if (-not $payload -or -not $bootstrap) { throw '7-Zip公式ReleaseにExtra archiveまたは7zr.exeがありません。' }
    [pscustomobject]@{
        id=$Dependency.Id; version=[string]$release.tag_name; archiveType='7z'
        artifacts=@(
            New-Artifact ([string]$payload.browser_download_url) (Get-GitHubAssetHash $payload) 'SHA256' ([string]$payload.name) 'payload'
            New-Artifact ([string]$bootstrap.browser_download_url) (Get-GitHubAssetHash $bootstrap) 'SHA256' ([string]$bootstrap.name) 'bootstrap'
        )
    }
}

function Resolve-Release($Dependency) {
    switch ($Dependency.Provider) {
        'Adoptium' { Resolve-AdoptiumRelease $Dependency }
        'BtbNGitHub' { Resolve-BtbNRelease $Dependency }
        'MavenCentral' { Resolve-MavenCentralRelease $Dependency }
        'ApacheDistribution' { Resolve-ApacheAntRelease $Dependency }
        'GitHubRelease' { Resolve-7ZipRelease $Dependency }
        default { throw "未対応の更新プロバイダーです: $($Dependency.Provider)" }
    }
}

function Compare-VersionText([string]$Current, [string]$Latest) {
    if (-not $Current) { return -1 }
    $left = [regex]::Matches($Current, '\d+') | ForEach-Object { [int64]$_.Value }
    $right = [regex]::Matches($Latest, '\d+') | ForEach-Object { [int64]$_.Value }
    $length = [Math]::Max($left.Count, $right.Count)
    for ($i=0; $i -lt $length; $i++) {
        $a = if ($i -lt $left.Count) { $left[$i] } else { 0 }
        $b = if ($i -lt $right.Count) { $right[$i] } else { 0 }
        if ($a -lt $b) { return -1 }
        if ($a -gt $b) { return 1 }
    }
    0
}

function Save-Download($Artifact, [string]$DependencyId) {
    $uri = [Uri]$Artifact.url
    if ($uri.Scheme -ne 'https') { throw "HTTPS以外の取得元です: $uri" }
    $name = [string]$Artifact.fileName
    $target = Join-Path $downloadRoot "$DependencyId-$name"
    $partial = "$target.partial"
    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    Invoke-WebRequest -Uri $uri -OutFile $partial -UseBasicParsing -Headers @{
        'User-Agent' = 'NicoCache_nl runtime dependency updater'
    }
    $actual = Get-Hash $partial ([string]$Artifact.algorithm)
    if ($actual -ne ([string]$Artifact.hash).ToLowerInvariant()) {
        Remove-Item -LiteralPath $partial -Force
        throw "$DependencyId の $($Artifact.algorithm) が一致しません。"
    }
    Move-Item -LiteralPath $partial -Destination $target -Force
    $target
}

function Expand-Release($Dependency, $Release, [hashtable]$Downloads, [string]$Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $Destination | Out-Null
    switch ($Release.archiveType) {
        'zip' { Expand-Archive -LiteralPath $Downloads['payload'] -DestinationPath $Destination -Force }
        '7z' {
            $extractor = Join-Path (Join-Path $ApplicationRoot $Dependency.ManagedPath) $Dependency.Executable
            if (-not (Test-Path -LiteralPath $extractor -PathType Leaf)) { $extractor = $Downloads['bootstrap'] }
            if (-not $extractor) { throw '7-Zip archiveを展開するbootstrap実行ファイルがありません。' }
            $arguments = @('x', $Downloads['payload'], "-o$Destination", '-y')
            $process = Start-Process -FilePath $extractor -ArgumentList $arguments -Wait -PassThru
            if ($process.ExitCode -ne 0) { throw "7-Zip archiveの展開に失敗しました: $($process.ExitCode)" }
        }
        default { throw "未対応のアーカイブ形式です: $($Release.archiveType)" }
    }
}

function Find-ContentRoot($Dependency, [string]$Stage) {
    $expected = if ($Dependency.Id -eq 'temurin') { Get-JavaExecutableName } else {
        [IO.Path]::GetFileName([string]$Dependency.Executable)
    }
    $candidate = Get-ChildItem -LiteralPath $Stage -Recurse -File -Filter $expected | Select-Object -First 1
    if (-not $candidate) { throw "展開内容に必要な実行ファイルがありません: $expected" }
    $root = $candidate.Directory
    $relativeExecutable = if ($Dependency.Id -eq 'temurin') { "bin\$expected" } else { [string]$Dependency.Executable }
    $segments = ($relativeExecutable -replace '/', '\').Split('\')
    for ($i=1; $i -lt $segments.Count; $i++) { $root = $root.Parent }
    $root.FullName
}

function Backup-AndReplaceDirectory([string]$Source, [string]$Destination, [string]$DependencyId) {
    $Destination = Assert-ManagedPath $Destination
    $backup = Join-Path $backupRoot ("{0}-{1:yyyyMMddHHmmssfff}" -f $DependencyId, (Get-Date))
    if (Test-Path -LiteralPath $Destination) { Move-Item -LiteralPath $Destination -Destination $backup }
    try { Move-Item -LiteralPath $Source -Destination $Destination }
    catch {
        if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
        if (Test-Path -LiteralPath $backup) { Move-Item -LiteralPath $backup -Destination $Destination }
        throw
    }
}

$installedState = Get-InstalledState
$results = foreach ($dependency in $definition.Dependencies) {
    if ($Id -and $dependency.Id -notin $Id) { continue }
    try {
        $release = Resolve-Release $dependency
        $current = Get-CurrentDependency $dependency $installedState
        $available = (Compare-VersionText $current.Version $release.version) -lt 0
        [pscustomobject]@{
            Id=$dependency.Id; Name=$dependency.DisplayName
            CurrentVersion=$current.Version; LatestVersion=$release.version
            Managed=$current.Managed; Path=$current.Path
            UpdateAvailable=$available; CanUpdate=$available -and $current.Managed
            Status='OK'; Release=$release; Definition=$dependency
        }
    } catch {
        $current = Get-CurrentDependency $dependency $installedState
        [pscustomobject]@{
            Id=$dependency.Id; Name=$dependency.DisplayName
            CurrentVersion=$current.Version; LatestVersion=$null
            Managed=$current.Managed; Path=$current.Path
            UpdateAvailable=$false; CanUpdate=$false
            Status=$_.Exception.Message; Release=$null; Definition=$dependency
        }
    }
}

$results | Select-Object Id, Name, CurrentVersion, LatestVersion, Managed,
    UpdateAvailable, CanUpdate, Status, Path | Format-Table -AutoSize -Wrap
if ($Mode -eq 'Check') { return }

$targets = @($results | Where-Object CanUpdate)
if ($targets.Count -eq 0) { Write-Output '更新可能な管理対象依存関係はありません。'; return }
if (-not $NonInteractive) {
    $answer = Read-Host ("{0}件を更新します。続行しますか? [y/N]" -f $targets.Count)
    if ($answer -notmatch '^(y|yes)$') { Write-Output '更新を中止しました。'; return }
}

foreach ($target in $targets) {
    $release = $target.Release
    $dependency = $target.Definition
    $destination = Assert-ManagedPath (Join-Path $ApplicationRoot $dependency.ManagedPath)

    if ($dependency.Id -eq 'bouncycastle') {
        $transaction = Join-Path $stagingRoot ('bouncycastle-' + [guid]::NewGuid())
        New-Item -ItemType Directory -Path $transaction | Out-Null
        foreach ($artifact in $release.artifacts) {
            $download = Save-Download $artifact $dependency.Id
            Copy-Item -LiteralPath $download -Destination (Join-Path $transaction $artifact.fileName)
        }
        $backup = Join-Path $backupRoot ("bouncycastle-{0:yyyyMMddHHmmssfff}" -f (Get-Date))
        New-Item -ItemType Directory -Path $backup | Out-Null
        try {
            foreach ($name in $dependency.Files) {
                $existing = Join-Path $destination $name
                if (Test-Path -LiteralPath $existing) { Copy-Item $existing $backup }
            }
            New-Item -ItemType Directory -Force -Path $destination | Out-Null
            foreach ($name in $dependency.Files) {
                Copy-Item (Join-Path $transaction $name) (Join-Path $destination $name) -Force
            }
            $installedState[$dependency.Id] = $release.version
            Save-InstalledState $installedState
            Write-Output "更新しました: $($dependency.DisplayName) $($release.version)"
        } catch {
            foreach ($name in $dependency.Files) {
                $saved = Join-Path $backup $name
                $current = Join-Path $destination $name
                if (Test-Path -LiteralPath $current) { Remove-Item -LiteralPath $current -Force }
                if (Test-Path -LiteralPath $saved) { Copy-Item $saved $current -Force }
            }
            throw
        }
        continue
    }

    $downloads = @{}
    foreach ($artifact in $release.artifacts) {
        $downloads[[string]$artifact.role] = Save-Download $artifact $dependency.Id
    }
    $stage = Join-Path $stagingRoot ($dependency.Id + '-' + [guid]::NewGuid())
    Expand-Release $dependency $release $downloads $stage
    $contentRoot = Find-ContentRoot $dependency $stage
    if ($dependency.UpdateMode -eq 'AfterExit') {
        [pscustomobject]@{
            Source=$contentRoot; Destination=$destination
            Id=$dependency.Id; Version=$release.version
        } | ConvertTo-Json | Set-Content -LiteralPath $pendingPath -Encoding UTF8
        Write-Output "終了後更新を準備しました: $($dependency.DisplayName) $($release.version)"
    } else {
        Backup-AndReplaceDirectory $contentRoot $destination $dependency.Id
        $installedState[$dependency.Id] = $release.version
        Save-InstalledState $installedState
        Write-Output "更新しました: $($dependency.DisplayName) $($release.version)"
    }
}