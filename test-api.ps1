param(
    [switch]$KeepWorkDir
)

$ErrorActionPreference = "Stop"
$functionalTest = Join-Path $PSScriptRoot "test-functional.ps1"
& $functionalTest -ApiOnly -KeepWorkDir:$KeepWorkDir
if ($LASTEXITCODE -ne 0) {
    throw "本体API契約テストに失敗しました"
}
