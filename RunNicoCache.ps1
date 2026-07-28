param(
    [ValidateSet(17, 21, 25)]
    [int]$JavaVersion,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$jarPath = Join-Path $root "NicoCache_nl.jar"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "NicoCache_nl.jar が見つかりません: $jarPath"
}

. (Join-Path $root "java-tool-selection.ps1")
$selectionParameters = @{ Candidates = @(Get-JavaToolCandidates "javaw") }
if ($PSBoundParameters.ContainsKey("JavaVersion")) {
    $selectionParameters.JavaVersion = $JavaVersion
}
$selectedJava = Select-JavaToolCandidate @selectionParameters
Write-Host "Java $($selectedJava.Major) を使用します: $($selectedJava.Path)"
$javaArguments = @("-jar", $jarPath) + @($Arguments)
Start-Process -FilePath $selectedJava.Path -ArgumentList $javaArguments -WorkingDirectory $root
