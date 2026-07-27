[CmdletBinding()]
param(
    [ValidateSet('Check', 'Update')]
    [string]$Mode = 'Check',
    [string[]]$Id,
    [string]$ApplicationRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$ManifestUri,
    [switch]$NonInteractive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$definitionPath = Join-Path $PSScriptRoot 'runtime-dependencies.psd1'
$definition = Import-PowerShellDataFile -LiteralPath $definitionPath
if (-not $ManifestUri) { $ManifestUri = $definition.ReleaseManifestUri }
if (-not ([Uri]$ManifestUri).Scheme.Equals('https', [StringComparison]::OrdinalIgnoreCase)) {
    throw '依存関係マニフェストはHTTPSである必要があります。'
}

$stateRoot = Join-Path $ApplicationRoot '.runtime-dependency-updater'
$downloadRoot = Join-Path $stateRoot 'downloads'
$stagingRoot = Join-Path $stateRoot 'staging'
$backupRoot = Join-Path $stateRoot 'backups'
$pendingPath = Join-Path $stateRoot 'pending-update.json'
New-Item -ItemType Directory -Force -Path $stateRoot, $downloadRoot, $stagingRoot, $backupRoot | Out-Null

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-ManagedPath([string]$Path) {
    $root = [IO.Path]::GetFullPath($ApplicationRoot).TrimEnd('\', '/')
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "管理対象外のパスです: $full"
    }
    return $full
}

function Invoke-VersionCommand($Dependency, [string]$Executable) {
    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { return $null }
    $output = & $Executable @($Dependency.VersionArguments) 2>&1 | Out-String
    $match = [regex]::Match($output, $Dependency.VersionPattern,
        [Text.RegularExpressions.RegexOptions]::Multiline)
    if ($match.Success) { return $match.Groups['version'].Value }
    return $null
}

function Get-CurrentDependency($Dependency) {
    $managedRoot = Assert-ManagedPath (Join-Path $ApplicationRoot $Dependency.ManagedPath)
    if ($Dependency.Id -eq 'bouncycastle') {
        $lockPath = Join-Path $ApplicationRoot $Dependency.VersionSource
        if (Test-Path -LiteralPath $lockPath) {
            $lock = Import-PowerShellDataFile -LiteralPath $lockPath
            return [pscustomobject]@{ Version = $lock.BouncyCastleVersion; Managed = $true; Path = $managedRoot }
        }
        $jar = Join-Path $managedRoot 'bcprov.jar'
        $version = if (Test-Path -LiteralPath $jar) { 'installed' } else { $null }
        return [pscustomobject]@{ Version = $version; Managed = $true; Path = $managedRoot }
    }

    $managedExe = Join-Path $managedRoot $Dependency.Executable
    $version = Invoke-VersionCommand $Dependency $managedExe
    if ($version) {
        return [pscustomobject]@{ Version = $version; Managed = $true; Path = $managedRoot }
    }

    if ($Dependency.Id -eq 'temurin') {
        $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
        if (-not $javaHome) { $javaHome = $env:JAVA_HOME }
        if ($javaHome) {
            $externalExe = Join-Path $javaHome 'bin\java.exe'
            $version = Invoke-VersionCommand $Dependency $externalExe
            if ($version) { return [pscustomobject]@{ Version = $version; Managed = $false; Path = $javaHome } }
        }
    }

    $commandName = switch ($Dependency.Id) {
        'ffmpeg' { 'ffmpeg.exe' }
        'ant' { 'ant.bat' }
        '7zip' { '7z.exe' }
        default { $null }
    }
    if ($commandName) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) {
            $version = Invoke-VersionCommand $Dependency $command.Source
            return [pscustomobject]@{ Version = $version; Managed = $false; Path = $command.Source }
        }
    }
    return [pscustomobject]@{ Version = $null; Managed = $true; Path = $managedRoot }
}

function Get-ReleaseManifest {
    $response = Invoke-WebRequest -Uri $ManifestUri -UseBasicParsing
    return ($response.Content | ConvertFrom-Json)
}

function Compare-Version([string]$Current, [string]$Latest) {
    if (-not $Current) { return -1 }
    $left = [regex]::Matches($Current, '\d+') | ForEach-Object { [int]$_.Value }
    $right = [regex]::Matches($Latest, '\d+') | ForEach-Object { [int]$_.Value }
    $length = [Math]::Max($left.Count, $right.Count)
    for ($i = 0; $i -lt $length; $i++) {
        $a = if ($i -lt $left.Count) { $left[$i] } else { 0 }
        $b = if ($i -lt $right.Count) { $right[$i] } else { 0 }
        if ($a -lt $b) { return -1 }
        if ($a -gt $b) { return 1 }
    }
    return 0
}

function Save-Download($Artifact, [string]$DependencyId) {
    $uri = [Uri]$Artifact.url
    if ($uri.Scheme -ne 'https') { throw "HTTPS以外の取得元です: $uri" }
    $name = [IO.Path]::GetFileName($uri.AbsolutePath)
    if (-not $name) { $name = "$DependencyId.download" }
    $target = Join-Path $downloadRoot "$DependencyId-$name"
    $partial = "$target.partial"
    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    Invoke-WebRequest -Uri $uri -OutFile $partial -UseBasicParsing
    $actual = Get-Sha256 $partial
    if ($actual -ne ([string]$Artifact.sha256).ToLowerInvariant()) {
        Remove-Item -LiteralPath $partial -Force
        throw "SHA-256が一致しません: $DependencyId"
    }
    Move-Item -LiteralPath $partial -Destination $target -Force
    return $target
}

function Expand-Artifact($Release, [string]$ArchivePath, [string]$Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $Destination | Out-Null
    switch ($Release.archiveType) {
        'zip' { Expand-Archive -LiteralPath $ArchivePath -DestinationPath $Destination -Force }
        'files' { Copy-Item -LiteralPath $ArchivePath -Destination $Destination }
        default { throw "未対応のアーカイブ形式です: $($Release.archiveType)" }
    }
}

function Backup-AndReplaceDirectory([string]$Source, [string]$Destination, [string]$Id) {
    $Destination = Assert-ManagedPath $Destination
    $backup = Join-Path $backupRoot ("{0}-{1:yyyyMMddHHmmssfff}" -f $Id, (Get-Date))
    if (Test-Path -LiteralPath $Destination) { Move-Item -LiteralPath $Destination -Destination $backup }
    try {
        Move-Item -LiteralPath $Source -Destination $Destination
    } catch {
        if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
        if (Test-Path -LiteralPath $backup) { Move-Item -LiteralPath $backup -Destination $Destination }
        throw
    }
}

$manifest = Get-ReleaseManifest
$results = @()
foreach ($dependency in $definition.Dependencies) {
    if ($Id -and $dependency.Id -notin $Id) { continue }
    $release = $manifest.dependencies | Where-Object id -eq $dependency.Id | Select-Object -First 1
    if (-not $release) { throw "マニフェストに依存関係がありません: $($dependency.Id)" }
    $current = Get-CurrentDependency $dependency
    $available = (Compare-Version $current.Version $release.version) -lt 0
    $results += [pscustomobject]@{
        Id = $dependency.Id
        Name = $dependency.DisplayName
        CurrentVersion = $current.Version
        LatestVersion = $release.version
        Managed = $current.Managed
        Path = $current.Path
        UpdateAvailable = $available
        CanUpdate = $available -and $current.Managed
        Release = $release
        Definition = $dependency
    }
}

$results | Select-Object Id, Name, CurrentVersion, LatestVersion, Managed, UpdateAvailable, CanUpdate, Path |
    Format-Table -AutoSize

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
        } catch {
            foreach ($name in $dependency.Files) {
                $saved = Join-Path $backup $name
                if (Test-Path $saved) { Copy-Item $saved (Join-Path $destination $name) -Force }
            }
            throw
        }
        continue
    }

    $artifact = $release.artifacts | Select-Object -First 1
    $download = Save-Download $artifact $dependency.Id
    $stage = Join-Path $stagingRoot ($dependency.Id + '-' + [guid]::NewGuid())
    Expand-Artifact $release $download $stage
    $contentRoot = if ($release.contentRoot) { Join-Path $stage $release.contentRoot } else { $stage }

    if ($dependency.UpdateMode -eq 'AfterExit') {
        $pending = [pscustomobject]@{ Source = $contentRoot; Destination = $destination; Id = $dependency.Id }
        $pending | ConvertTo-Json | Set-Content -LiteralPath $pendingPath -Encoding UTF8
        Write-Output "終了後更新を準備しました: $($dependency.DisplayName)"
    } else {
        Backup-AndReplaceDirectory $contentRoot $destination $dependency.Id
        Write-Output "更新しました: $($dependency.DisplayName) $($release.version)"
    }
}
