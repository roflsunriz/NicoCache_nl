[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $root 'java-tool-selection.ps1')

function New-Candidate {
    param(
        [Parameter(Mandatory)][int]$Major,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][bool]$IsTemurin
    )

    [pscustomobject]@{
        Major = $Major
        Path = $Path
        IsTemurin = $IsTemurin
    }
}

$candidates = @(
    New-Candidate -Major 21 -Path 'temurin-21' -IsTemurin $true
    New-Candidate -Major 25 -Path 'other-25' -IsTemurin $false
    New-Candidate -Major 25 -Path 'temurin-25' -IsTemurin $true
    New-Candidate -Major 17 -Path 'temurin-17' -IsTemurin $true
)

$default = Select-JavaToolCandidate -Candidates $candidates
if ($default.Major -ne 25 -or $default.Path -ne 'temurin-25') {
    throw '既定のJavaツールとしてTemurin 25が選択されませんでした'
}

$explicit = Select-JavaToolCandidate -Candidates $candidates -JavaVersion 17
if ($explicit.Major -ne 17 -or $explicit.Path -ne 'temurin-17') {
    throw '明示した互換性検証用Temurin 17が選択されませんでした'
}

$missingDefaultFailed = $false
try {
    Select-JavaToolCandidate -Candidates @(
        New-Candidate -Major 25 -Path 'other-25' -IsTemurin $false
        New-Candidate -Major 21 -Path 'temurin-21' -IsTemurin $true
    ) | Out-Null
} catch {
    $missingDefaultFailed = $_.Exception.Message -match 'Temurin JDK 25'
}
if (-not $missingDefaultFailed) {
    throw 'Temurin 25がない環境で別のJDKへ暗黙にフォールバックしました'
}

$actualJavac = Select-JavaToolCandidate `
    -Candidates @(Get-JavaToolCandidates 'javac')
$actualJava = Select-JavaToolCandidate `
    -Candidates @(Get-JavaToolCandidates 'java')
if ($actualJavac.Major -ne 25 -or $actualJava.Major -ne 25) {
    throw '実環境の既定java/javacがTemurin 25ではありません'
}
Assert-TemurinJavaRuntime -JavaPath $actualJava.Path -JavaVersion 25 | Out-Null

Write-Output 'Java tool selection tests passed'
