param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$jarTool = (Get-Command jar -ErrorAction Stop).Source
$javapTool = (Get-Command javap -ErrorAction Stop).Source

$classNames = & $jarTool tf $resolvedJar |
    Where-Object { $_ -like "dareka/*.class" -and
        $_ -notlike "dareka/internal/*" -and
        $_ -notlike "*module-info.class" } |
    ForEach-Object { ($_ -replace "/", ".") -replace "\.class$", "" } |
    Sort-Object -Unique

if ($LASTEXITCODE -ne 0 -or $classNames.Count -eq 0) {
    throw "JAR から dareka.* クラスを列挙できませんでした: $resolvedJar"
}

$entries = [System.Collections.Generic.List[string]]::new()
foreach ($className in $classNames) {
    $output = & $javapTool -classpath $resolvedJar -protected -s $className 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "javap に失敗しました: $className`n$($output -join [Environment]::NewLine)"
    }

    $normalized = @($output |
        Where-Object { $_ -notmatch '^Compiled from ' } |
        ForEach-Object { ($_ -replace '\s+', ' ').Trim() } |
        Where-Object { $_.Length -gt 0 })

    if ($normalized.Count -eq 0 -or $normalized[0] -notmatch '^(public|protected)\s+') {
        continue
    }

    $entries.Add("TYPE $className | $($normalized[0])")
    for ($index = 1; $index -lt $normalized.Count; $index++) {
        $line = $normalized[$index]
        if ($line -eq '}' -or $line -match '^descriptor:') {
            continue
        }
        $entry = "MEMBER $className | $line"
        if ($index + 1 -lt $normalized.Count -and
                $normalized[$index + 1] -match '^descriptor:') {
            $entry += " | $($normalized[$index + 1])"
            $index++
        }
        $entries.Add($entry)
    }
}

if ($OutputPath) {
    $fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $fullOutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    $canonical = ($entries -join "`n") + "`n"
    [System.IO.File]::WriteAllText($fullOutputPath, $canonical,
            [System.Text.UTF8Encoding]::new($false))
} else {
    $entries
}
