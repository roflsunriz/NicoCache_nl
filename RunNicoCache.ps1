param(
    [ValidateSet(17, 21, 25)]
    [int]$JavaVersion
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot
. .\java-tool-selection.ps1
$selectedJava = Select-JavaToolCandidate (Get-JavaToolCandidates "javaw") $JavaVersion
Write-Host "Java $($selectedJava.Major) を使用します: $($selectedJava.Path)"
Start-Process -FilePath $selectedJava.Path -ArgumentList "-jar", "NicoCache_nl.jar"
