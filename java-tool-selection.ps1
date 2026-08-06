function Get-JavaRuntimeMetadata {
    param(
        [Parameter(Mandatory)]
        [string]$JavaPath
    )

    $versionLines = @(
        & $JavaPath --version 2>&1 | ForEach-Object { [string]$_ }
    )
    if ($LASTEXITCODE -ne 0 -or $versionLines.Count -eq 0) {
        throw "Javaランタイムのバージョンを取得できません: $JavaPath"
    }
    $firstLine = [string]($versionLines | Select-Object -First 1)
    if ($firstLine -notmatch '(?:java|openjdk)\s+(?:version\s+)?(\d+)(?:\.\d+)?') {
        throw "Javaランタイムのメジャーバージョンを判定できません: $JavaPath"
    }
    $major = [int]$Matches[1]
    [pscustomobject]@{
        Major = $major
        IsTemurin = ($versionLines -join "`n") -match '(?i)\bTemurin\b'
        Path = (Resolve-Path -LiteralPath $JavaPath).Path
    }
}

function Assert-TemurinJavaRuntime {
    param(
        [Parameter(Mandatory)]
        [string]$JavaPath,
        [int]$JavaVersion = 25
    )

    $metadata = Get-JavaRuntimeMetadata -JavaPath $JavaPath
    if (-not $metadata.IsTemurin -or $metadata.Major -ne $JavaVersion) {
        throw "Eclipse Temurin JDK ${JavaVersion}が必要です: $JavaPath"
    }
    $metadata
}

function Get-JavaToolCandidates {
    param(
        [Parameter(Mandatory)]
        [string]$CommandName
    )

    $paths = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $javaBin = Join-Path $env:JAVA_HOME 'bin'
        $paths.Add((Join-Path $javaBin "$CommandName.exe"))
        $paths.Add((Join-Path $javaBin $CommandName))
    }
    Get-Command $CommandName -All -ErrorAction SilentlyContinue |
        ForEach-Object {
            $commandPath = if ($_.Source) { $_.Source } else { $_.Path }
            if ($commandPath) { $paths.Add($commandPath) }
        }

    $seen = @{}
    foreach ($path in $paths) {
        if (-not $path -or $seen.ContainsKey($path)) { continue }
        $seen[$path] = $true
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }

        try {
            $versionLines = @(& $path --version 2>&1 | ForEach-Object { [string]$_ })
            $versionOutput = [string]($versionLines | Select-Object -First 1)
            if ($versionOutput -match '(?:javac|java|openjdk)\s+(?:version\s+)?(\d+)(?:\.\d+)?') {
                $major = [int]$Matches[1]
                $javaName = if ([System.IO.Path]::GetExtension($path) -eq '.exe') {
                    'java.exe'
                } else {
                    'java'
                }
                $runtimePath = if ($CommandName -eq 'java') {
                    $path
                } else {
                    Join-Path (Split-Path -Parent $path) $javaName
                }
                if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) {
                    continue
                }
                $runtimeMetadata = Get-JavaRuntimeMetadata -JavaPath $runtimePath
                [pscustomobject]@{
                    Major = $major
                    Path = (Resolve-Path -LiteralPath $path).Path
                    IsTemurin = $runtimeMetadata.IsTemurin
                }
            }
        } catch {
            # 実行できない候補は対応版として扱わない。
        }
    }
}

function Select-JavaToolCandidate {
    param(
        [Parameter(Mandatory)]
        [object[]]$Candidates,
        [int]$JavaVersion
    )

    $supportedCandidates = @($Candidates |
        Where-Object { $_.Major -in @(17, 21, 25) -and $_.IsTemurin } |
        Sort-Object Major -Descending)
    if (-not $supportedCandidates) {
        throw "対応する Eclipse Temurin JDK (17、21、25) が見つかりません"
    }

    $requestedVersion = if ($PSBoundParameters.ContainsKey("JavaVersion")) {
        $JavaVersion
    } else { 25 }
    $selected = $supportedCandidates |
        Where-Object Major -eq $requestedVersion |
        Select-Object -First 1
    if (-not $selected) {
        $available = ($supportedCandidates | ForEach-Object Major) -join ", "
        throw "Eclipse Temurin JDK $requestedVersion が見つかりません。利用可能: $available"
    }
    $selected
}
