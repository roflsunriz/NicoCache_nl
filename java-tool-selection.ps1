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
            $versionOutput = (& $path --version 2>&1 | Select-Object -First 1).ToString()
            if ($versionOutput -match '(?:javac|java|openjdk)\s+(?:version\s+)?(\d+)(?:\.\d+)?') {
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

function Select-JavaToolCandidate {
    param(
        [Parameter(Mandatory)]
        [object[]]$Candidates,
        [int]$JavaVersion
    )

    $supportedCandidates = @($Candidates |
        Where-Object { $_.Major -in @(17, 21, 25) } |
        Sort-Object Major -Descending -Unique)
    if (-not $supportedCandidates) {
        throw "対応する Java (17、21、25) が見つかりません"
    }

    $selected = if ($PSBoundParameters.ContainsKey("JavaVersion")) {
        $supportedCandidates | Where-Object Major -eq $JavaVersion | Select-Object -First 1
    } else {
        $supportedCandidates | Select-Object -First 1
    }
    if (-not $selected) {
        $available = ($supportedCandidates | ForEach-Object Major) -join ", "
        throw "Java $JavaVersion が見つかりません。利用可能: $available"
    }
    $selected
}
