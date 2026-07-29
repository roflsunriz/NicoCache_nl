[CmdletBinding()]
param(
    [ValidateSet('check', 'source-check', 'compatibility', 'serve', 'headless', 'test')]
    [string]$Command = 'check',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'
$LabRoot = $PSScriptRoot
$RepositoryRoot = (Resolve-Path (Join-Path $LabRoot '..\..')).Path
$BuildRoot = Join-Path $RepositoryRoot '.cache\nlfilter-lab'
$Classes = Join-Path $BuildRoot 'classes'
$SourceList = Join-Path $BuildRoot 'sources.txt'

New-Item -ItemType Directory -Force -Path $Classes | Out-Null

$Sources = @(
    Get-ChildItem -LiteralPath (Join-Path $LabRoot 'src') -Recurse -Filter '*.java' -File
    if ($Command -eq 'test') {
        Get-ChildItem -LiteralPath (Join-Path $LabRoot 'tests') -Recurse -Filter '*.java' -File
    }
) | Sort-Object FullName

$Sources.FullName | Set-Content -LiteralPath $SourceList -Encoding utf8NoBOM
& javac -encoding UTF-8 -d $Classes "@$SourceList"
if ($LASTEXITCODE -ne 0) {
    throw "nlFilter Lab のコンパイルに失敗しました (exit code: $LASTEXITCODE)"
}

$MainClass = if ($Command -eq 'test') { 'nlfilterlab.NlFilterLabTests' } else { 'nlfilterlab.Main' }
$JavaArguments = @(
    '-Dfile.encoding=UTF-8'
    "-Dnlfilterlab.root=$LabRoot"
    "-Dnlfilterlab.repository=$RepositoryRoot"
    '-cp'
    $Classes
    $MainClass
)
if ($Command -ne 'test') {
    $JavaArguments += $Command
}
$JavaArguments += $Arguments

& java @JavaArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
