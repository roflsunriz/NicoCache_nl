param(
    [ValidateSet(17, 21, 25)]
    [int]$JavaVersion,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$jarPath = Join-Path $root "NicoCacheLauncher.jar"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "NicoCacheLauncher.jar が見つかりません。先に build-javac.ps1 を実行してください: $jarPath"
}

. (Join-Path $root "java-tool-selection.ps1")
$headless = @($Arguments) -contains "--headless"
$javaTool = if ($headless) { "java" } else { "javaw" }
$selectionParameters = @{ Candidates = @(Get-JavaToolCandidates $javaTool) }
if ($PSBoundParameters.ContainsKey("JavaVersion")) {
    $selectionParameters.JavaVersion = $JavaVersion
}
$selectedJava = Select-JavaToolCandidate @selectionParameters
Write-Host "Java $($selectedJava.Major) を使用します: $($selectedJava.Path)"
$javaArguments = @("-jar", $jarPath) + @($Arguments)
if ($headless) {
    & $selectedJava.Path @javaArguments
    exit $LASTEXITCODE
}
Start-Process -FilePath $selectedJava.Path -ArgumentList $javaArguments -WorkingDirectory $root
