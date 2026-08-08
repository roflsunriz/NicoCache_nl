#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Linux', 'MacOS')]
    [string]$Platform,

    [ValidatePattern('^\d+(?:\.\d+){0,2}$')]
    [string]$AppVersion = '0.2.2'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Platform)) {
    if ($IsLinux) { $Platform = 'Linux' }
    elseif ($IsMacOS) { $Platform = 'MacOS' }
    else { throw 'LinuxまたはmacOS上で実行するか、-Platformを指定してください' }
}
$hostPlatform = if ($IsLinux) { 'Linux' } elseif ($IsMacOS) { 'MacOS' } else { 'Other' }
if ($Platform -ne $hostPlatform) {
    throw "Unixパッケージの検証は対象OS上で実行してください (host=$hostPlatform, target=$Platform)"
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$workRoot = Join-Path $root (Join-Path '.test-work' ('standalone-updater-' + $Platform.ToLowerInvariant()))
$outputRoot = Join-Path $workRoot 'output'
$bundleName = 'NicoCache_nl Updater' + $(if ($Platform -eq 'MacOS') { '.app' } else { '' })
$bundle = Join-Path $outputRoot $bundleName
$contentRoot = if ($Platform -eq 'MacOS') { Join-Path $bundle 'Contents' } else { $bundle }
$applicationDirectory = if ($Platform -eq 'Linux') {
    Join-Path $bundle 'lib/app'
} else {
    Join-Path $contentRoot 'app'
}
$runtimeDirectory = if ($Platform -eq 'Linux') {
    Join-Path $bundle 'lib/runtime'
} else {
    Join-Path $contentRoot 'runtime/Contents/Home'
}
$architecture = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64' { 'x64' }
    'Arm64' { 'arm64' }
    'X86' { 'x86' }
    default { throw '未対応のCPUアーキテクチャです' }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-File([string]$Path) {
    Assert-True (Test-Path -LiteralPath $Path -PathType Leaf) "ファイルがありません: $Path"
}
function Get-RequiredCommand([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "必要な検証コマンドがありません: $Name" }
    if ($command.Source) { return $command.Source }
    return $command.Path
}
function Invoke-Updater([string]$Launcher, [string[]]$Arguments) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Launcher
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { [void]$info.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    [void]$process.Start()
    if (-not $process.WaitForExit(120000)) {
        $process.Kill($true)
        throw "アップデーターがタイムアウトしました: $($Arguments -join ' ')"
    }
    $output = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    Assert-True ($process.ExitCode -eq 0) "アップデーターに失敗しました: $output"
    return $output
}

Assert-True (Test-Path -LiteralPath $bundle -PathType Container) "アップデーターアプリイメージがありません: $bundle"
$launcher = if ($Platform -eq 'MacOS') {
    Join-Path $contentRoot 'MacOS/NicoCache_nl Updater'
} else {
    Join-Path $bundle 'bin/NicoCache_nl Updater'
}
Assert-File $launcher
Assert-File (Join-Path $applicationDirectory 'NicoCacheUpdater.jar')
Assert-File (Join-Path $runtimeDirectory 'lib/modules')
Assert-True (-not @(Get-ChildItem -LiteralPath $bundle -Recurse -File -Filter '*.ps1').Count) `
    'PowerShellスクリプトがアップデーターへ混入しています'

$target = Join-Path $workRoot 'target'
if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $target 'jre/bin') | Out-Null
Set-Content -LiteralPath (Join-Path $target 'NicoCache_nl') -Value 'launcher' -Encoding utf8
Set-Content -LiteralPath (Join-Path $target 'NicoCache_nl.jar') -Value 'jar' -Encoding utf8
Set-Content -LiteralPath (Join-Path $target 'NicoCache_nl.version') -Value '1.0.1' -Encoding ascii
$validation = Invoke-Updater $launcher @('--validate-target-root', '--app-root', $target)
Assert-True ($validation.Trim() -eq (Resolve-Path -LiteralPath $target).Path) `
    '更新対象の検証結果が不正です'
$applicationCheck = Invoke-Updater $launcher @('--headless', '--application-check', '--app-root', $target)
Assert-True ($applicationCheck.Contains('NicoCache_nl-')) 'ヘッドレス本体更新チェックがありません'
$selfTest = Invoke-Updater $launcher @('--self-test', '--app-root', $target)
Assert-True ($selfTest.Contains('SELF_TEST_OK')) '自己診断マーカーがありません'
Assert-True ($selfTest.Contains('SYSTEM_DEPENDENCY_SELF_TEST_OK')) '依存関係自己診断がありません'
if ($Platform -eq 'Linux') {
    foreach ($extension in @('.deb', '.rpm')) {
        $package = Get-ChildItem -LiteralPath $outputRoot -Filter "*linux-$architecture$extension" -File |
            Select-Object -First 1
        Assert-True ($null -ne $package) "Linuxアップデーター${extension}がありません"
        if ($extension -eq '.deb') { & (Get-RequiredCommand 'dpkg-deb') --info $package.FullName | Out-Null }
        else { & (Get-RequiredCommand 'rpm') -qpl $package.FullName | Out-Null }
        Assert-True ($LASTEXITCODE -eq 0) "Linuxアップデーター${extension}の検証に失敗しました"
    }
} else {
    foreach ($extension in @('.pkg', '.dmg')) {
        $package = Get-ChildItem -LiteralPath $outputRoot -Filter "*macos-$architecture$extension" -File |
            Select-Object -First 1
        Assert-True ($null -ne $package) "macOSアップデーター${extension}がありません"
        Assert-True ($package.Length -gt 0) "macOSアップデーター${extension}が空です"
    }
}

Write-Output "${Platform}独立アップデーターの構造・CLI自己診断テストに成功しました"
