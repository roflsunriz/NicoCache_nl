param(
    [switch]$KeepWorkDir,
    [string]$LibraryDirectory = (Join-Path $PSScriptRoot 'lib')
)

$ErrorActionPreference = "Stop"
$functionalTest = Join-Path $PSScriptRoot "test-functional.ps1"
& $functionalTest -ApiOnly -KeepWorkDir:$KeepWorkDir `
    -LibraryDirectory $LibraryDirectory
if ($LASTEXITCODE -ne 0) {
    throw "本体API契約テストに失敗しました"
}
