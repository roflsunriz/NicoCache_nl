#Requires -Version 7.0
[CmdletBinding()]
param([switch]$BuildMsi)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = Join-Path $root '.test-work\updater-tests'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-File([string]$Path) { Assert-True (Test-Path -LiteralPath $Path -PathType Leaf) "File missing: $Path" }
function Assert-Directory([string]$Path) { Assert-True (Test-Path -LiteralPath $Path -PathType Container) "Directory missing: $Path" }
function Invoke-MsiExec([string[]]$Arguments, [string]$FailureMessage) {
    $process = Start-Process msiexec.exe -ArgumentList $Arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) {
        throw "$FailureMessage (ExitCode: $($process.ExitCode))"
    }
}

# Compile production source and dependency-free unit tests.
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
& javac --release 11 -encoding UTF-8 -Xlint:all -d $classes `
    (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') `
    (Join-Path $root 'updater\test\dareka\updater\NicoCacheUpdaterTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Updater javac/unit-test compilation failed' }
& java -cp $classes dareka.updater.NicoCacheUpdaterTest
if ($LASTEXITCODE -ne 0) { throw 'Updater Java unit tests failed' }

# Source/security invariants.
$source = Get-Content (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') -Raw
foreach ($required in @('tabs.addTab("NicoCache_nl"', 'tabs.addTab("外部依存関係"',
        'available_lts_releases', 'MSIのSHA-256が一致しません', 'TESTED_LTS', '17, 21')) {
    Assert-True $source.Contains($required) "Updater source invariant missing: $required"
}
Assert-True (-not $source.Contains('RuntimeDependencyUpdaterGUI')) 'Standalone updater must not embed the old GUI'
$definition = Import-PowerShellDataFile (Join-Path $root 'extensions\runtime-dependencies.psd1')
Assert-True ($definition.SchemaVersion -ge 3) 'Runtime dependency schema is obsolete'
Assert-True ((@($definition.Dependencies.Id | Sort-Object) -join ',') -eq '7zip,ant,bouncycastle,ffmpeg,temurin') 'Dependency set mismatch'
$temurin = $definition.Dependencies | Where-Object Id -eq temurin
Assert-True ((@($temurin.SupportedLtsVersions) -join ',') -eq '17,21') 'Supported Temurin LTS mismatch'
Assert-True ($temurin.RecommendedLtsVersion -eq 21) 'Recommended Temurin LTS mismatch'

# End-of-process runtime replacement transaction.
$app = Join-Path $work 'fake-app'
$state = Join-Path $app '.runtime-dependency-updater'
$oldRuntime = Join-Path $app 'runtime'
$newRuntime = Join-Path $state 'staging\runtime-new'
New-Item -ItemType Directory -Path $oldRuntime, $newRuntime -Force | Out-Null
Set-Content (Join-Path $oldRuntime 'marker.txt') old -Encoding ascii
Set-Content (Join-Path $newRuntime 'marker.txt') new -Encoding ascii
@{ Source=$newRuntime; Destination=$oldRuntime; Id='temurin'; Version='21.0.9' } |
    ConvertTo-Json | Set-Content (Join-Path $state 'pending-update.json') -Encoding utf8
& (Join-Path $root 'extensions\apply-pending-runtime-update.ps1') -ApplicationRoot $app
Assert-True ((Get-Content (Join-Path $oldRuntime 'marker.txt') -Raw).Trim() -eq 'new') 'Runtime was not replaced'
Assert-True (-not (Test-Path (Join-Path $state 'pending-update.json'))) 'Pending state was not removed'
$installed = Get-Content (Join-Path $state 'installed-versions.json') -Raw | ConvertFrom-Json
Assert-True ($installed.temurin -eq '21.0.9') 'Installed runtime state was not persisted'
Assert-True (@(Get-ChildItem (Join-Path $state 'backups') -Directory).Count -eq 1) 'Runtime backup missing'

# Reject destination outside the managed application root.
$escapeSource = Join-Path $state 'staging\escape'
New-Item -ItemType Directory -Path $escapeSource -Force | Out-Null
$outside = Join-Path $work 'outside-runtime'
@{ Source=$escapeSource; Destination=$outside; Id='temurin'; Version='25' } |
    ConvertTo-Json | Set-Content (Join-Path $state 'pending-update.json') -Encoding utf8
$rejected = $false
try { & (Join-Path $root 'extensions\apply-pending-runtime-update.ps1') -ApplicationRoot $app }
catch { $rejected = $true }
Assert-True $rejected 'Path traversal/out-of-root destination was accepted'
Assert-True (-not (Test-Path $outside)) 'Updater wrote outside application root'
Remove-Item (Join-Path $state 'pending-update.json') -Force -ErrorAction SilentlyContinue

# Build a self-contained AppImage and optionally MSI.
$packageType = if ($BuildMsi) { 'All' } else { 'AppImage' }
& (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -PackageType $packageType -AppVersion 0.1.0
$appImage = Join-Path $root '.test-work\standalone-updater\output\NicoCache_nl Updater'
Assert-Directory $appImage
Assert-File (Join-Path $appImage 'NicoCache_nl Updater.exe')
Assert-File (Join-Path $appImage 'runtime\lib\modules')
Assert-File (Join-Path $appImage 'app\NicoCacheUpdater.jar')

# Independence: fake NicoCache_nl root is deliberately incomplete/broken.
$brokenRoot = Join-Path $work 'broken-nicocache'
New-Item -ItemType Directory -Path $brokenRoot | Out-Null
Set-Content (Join-Path $brokenRoot 'version.txt') 'broken-version' -Encoding ascii
$process = Start-Process -FilePath (Join-Path $appImage 'NicoCache_nl Updater.exe') `
    -ArgumentList '--app-root', $brokenRoot -PassThru
Start-Sleep -Seconds 5
Assert-True (-not $process.HasExited) 'Standalone updater did not remain running with broken/missing NicoCache_nl'
Stop-Process -Id $process.Id -Force

if ($BuildMsi) {
    $msi = Get-ChildItem (Join-Path $root '.test-work\standalone-updater\output') -Filter '*.msi' -File | Select-Object -First 1
    Assert-True ($null -ne $msi -and $msi.Length -gt 0) 'Updater MSI was not generated'
    $msiLog = Join-Path $work 'updater-msi.log'
    $installedRoot = Join-Path $env:ProgramFiles 'NicoCache_nl Updater'
    try {
        Invoke-MsiExec @('/i', $msi.FullName, '/qn', '/norestart', '/l*v', $msiLog) `
            'Updater MSI install failed'
        $installedExe = Join-Path $installedRoot 'NicoCache_nl Updater.exe'
        Assert-File $installedExe
        Assert-File (Join-Path $installedRoot 'runtime\lib\modules')
        $installedProcess = Start-Process -FilePath $installedExe `
            -ArgumentList '--app-root', $brokenRoot -PassThru
        Start-Sleep -Seconds 5
        Assert-True (-not $installedProcess.HasExited) 'Installed updater failed to launch independently'
        Stop-Process -Id $installedProcess.Id -Force
    }
    finally {
        Invoke-MsiExec @('/x', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi-uninstall.log')) `
            'Updater MSI uninstall failed'
    }
    Assert-True (-not (Test-Path -LiteralPath $installedRoot)) 'Updater MSI left its install directory behind'
}
Write-Output 'Standalone updater automated tests passed'
