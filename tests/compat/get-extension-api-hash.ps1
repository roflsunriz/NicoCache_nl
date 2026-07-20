param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath,
    [switch]$Detailed
)

$ErrorActionPreference = "Stop"

$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$jarTool = (Get-Command jar -ErrorAction Stop).Source
$javapTool = (Get-Command javap -ErrorAction Stop).Source

$classNames = & $jarTool tf $resolvedJar |
    Where-Object { $_ -like "dareka/*.class" -and $_ -notlike "*module-info.class" } |
    ForEach-Object { ($_ -replace "/", ".") -replace "\.class$", "" } |
    Sort-Object -Unique

if ($LASTEXITCODE -ne 0 -or $classNames.Count -eq 0) {
    throw "JAR から dareka.* クラスを列挙できませんでした: $resolvedJar"
}

$blocks = [System.Collections.Generic.List[string]]::new()
$publicTypeCount = 0

foreach ($className in $classNames) {
    $output = & $javapTool -classpath $resolvedJar -protected -s $className 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "javap に失敗しました: $className`n$($output -join [Environment]::NewLine)"
    }

    $normalized = $output |
        Where-Object { $_ -notmatch '^Compiled from ' } |
        ForEach-Object { ($_ -replace '\s+', ' ').Trim() } |
        Where-Object { $_.Length -gt 0 }

    if ($normalized.Count -eq 0) {
        continue
    }

    $declaration = $normalized[0]
    if ($declaration -notmatch '^(public|protected)\s+') {
        continue
    }

    $publicTypeCount++
    $blocks.Add("TYPE $className")
    foreach ($line in $normalized) {
        $blocks.Add($line)
    }
}

$canonical = ($blocks -join "`n") + "`n"
$bytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $hash = [System.BitConverter]::ToString($sha256.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()
} finally {
    $sha256.Dispose()
}

if ($Detailed) {
    [pscustomobject]@{
        Hash = $hash
        PublicTypeCount = $publicTypeCount
        CanonicalLineCount = $blocks.Count
        JarPath = $resolvedJar
    }
} else {
    $hash
}
