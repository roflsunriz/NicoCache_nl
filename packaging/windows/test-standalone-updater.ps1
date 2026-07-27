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
function Assert-File([string]$Path) {
    Assert-True (Test-Path -LiteralPath $Path -PathType Leaf) "File missing: $Path"
}
function Assert-Directory([string]$Path) {
    Assert-True (Test-Path -LiteralPath $Path -PathType Container) "Directory missing: $Path"
}
function Invoke-MsiExec([string[]]$Arguments, [string]$FailureMessage) {
    $quotedArguments = $Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }
    $process = Start-Process msiexec.exe -ArgumentList ($quotedArguments -join ' ') -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) {
        throw "$FailureMessage (ExitCode: $($process.ExitCode))"
    }
}
function Invoke-UpdaterCli(
        [string]$Executable,
        [string[]]$Arguments,
        [int[]]$ExpectedExitCodes = @(0),
        [int]$TimeoutSeconds = 180) {
    $argumentLine = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
    $process = Start-Process -FilePath $Executable -ArgumentList $argumentLine -PassThru
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Updater CLI timed out: $Executable $argumentLine"
    }
    Assert-True ($process.ExitCode -in $ExpectedExitCodes) `
        "Unexpected updater exit code $($process.ExitCode): $Executable $argumentLine"
    $process.ExitCode
}
function Assert-PackagedEngine([string]$UpdaterRoot) {
    $engine = Join-Path $UpdaterRoot 'app\extensions'
    Assert-Directory $engine
    foreach ($name in @(
            'update-runtime-dependencies.ps1',
            'runtime-dependencies.psd1',
            'apply-pending-runtime-update.ps1')) {
        Assert-File (Join-Path $engine $name)
    }
    $engineSource = Get-Content -LiteralPath (Join-Path $engine 'update-runtime-dependencies.ps1') -Raw
    Assert-True ($engineSource.Contains('function Resolve-AdoptiumRelease')) `
        'Packaged dependency updater is not the real engine'
    Assert-True (-not $engineSource.Contains(
            "Join-Path `$ApplicationRoot 'extensions\update-runtime-dependencies.ps1'")) `
        'Packaged dependency updater recursively delegates to the target installation'
}
function Invoke-DependencyE2E([string]$Executable, [string]$UpdaterRoot, [string]$TargetRoot) {
    Remove-Item -LiteralPath $TargetRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $TargetRoot | Out-Null
    Set-Content (Join-Path $TargetRoot 'version.txt') 'broken-version' -Encoding ascii

    Invoke-UpdaterCli $Executable @('--self-test', '--app-root', $TargetRoot) | Out-Null
    Invoke-UpdaterCli $Executable @(
        '--dependency-check', '--app-root', $TargetRoot, '--java-major', '21') | Out-Null

    Assert-Directory (Join-Path $TargetRoot '.runtime-dependency-updater')
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $UpdaterRoot '.runtime-dependency-updater'))) `
        'Dependency updater wrote state into the updater installation'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $UpdaterRoot 'runtime\bin\java.exe'))) `
        'Dependency updater modified the updater private runtime'

    # Safety invariant: updater and target roots may never be identical.
    Invoke-UpdaterCli $Executable @('--self-test', '--app-root', $UpdaterRoot) @(1) | Out-Null
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
foreach ($required in @(
        'tabs.addTab("NicoCache_nl"',
        'tabs.addTab("外部依存関係"',
        'available_lts_releases',
        'MSIのSHA-256が一致しません',
        'TESTED_LTS',
        '17, 21',
        'updaterRoot.resolve("app").resolve("extensions")',
        '--dependency-check',
        'Updater自身とNicoCache_nl対象ルートが分離されていません')) {
    Assert-True $source.Contains($required) "Updater source invariant missing: $required"
}
Assert-True (-not $source.Contains('RuntimeDependencyUpdaterGUI')) `
    'Standalone updater must not embed the old GUI'
$definition = Import-PowerShellDataFile (Join-Path $root 'extensions\runtime-dependencies.psd1')
Assert-True ($definition.SchemaVersion -ge 3) 'Runtime dependency schema is obsolete'
Assert-True ((@($definition.Dependencies.Id | Sort-Object) -join ',') -eq `
    '7zip,ant,bouncycastle,ffmpeg,temurin') 'Dependency set mismatch'
$temurin = $definition.Dependencies | Where-Object Id -eq temurin
Assert-True ((@($temurin.SupportedLtsVersions) -join ',') -eq '17,21') `
    'Supported Temurin LTS mismatch'
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
Assert-True ((Get-Content (Join-Path $oldRuntime 'marker.txt') -Raw).Trim() -eq 'new') `
    'Runtime was not replaced'
Assert-True (-not (Test-Path (Join-Path $state 'pending-update.json'))) `
    'Pending state was not removed'
$installed = Get-Content (Join-Path $state 'installed-versions.json') -Raw | ConvertFrom-Json
Assert-True ($installed.temurin -eq '21.0.9') 'Installed runtime state was not persisted'
Assert-True (@(Get-ChildItem (Join-Path $state 'backups') -Directory).Count -eq 1) `
    'Runtime backup missing'

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

# Build self-contained AppImage/MSI and execute the installed functional path.
$packageType = if ($BuildMsi) { 'All' } else { 'AppImage' }
& (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') `
    -PackageType $packageType -AppVersion 0.1.0
$appImage = Join-Path $root '.test-work\standalone-updater\output\NicoCache_nl Updater'
Assert-Directory $appImage
$appImageExe = Join-Path $appImage 'NicoCache_nl Updater.exe'
Assert-File $appImageExe
Assert-File (Join-Path $appImage 'runtime\lib\modules')
Assert-File (Join-Path $appImage 'app\NicoCacheUpdater.jar')
Assert-PackagedEngine $appImage
Invoke-DependencyE2E $appImageExe $appImage (Join-Path $work 'appimage-target')

# GUI smoke remains separate from functional CLI E2E: the same invokeDependencyUpdater method is used.
$guiTarget = Join-Path $work 'gui-target'
New-Item -ItemType Directory -Path $guiTarget | Out-Null
$process = Start-Process -FilePath $appImageExe -ArgumentList `
    ('--app-root "' + $guiTarget + '"') -PassThru
Start-Sleep -Seconds 5
Assert-True (-not $process.HasExited) 'Standalone updater GUI did not remain running'
Stop-Process -Id $process.Id -Force

if ($BuildMsi) {
    $msi = Get-ChildItem (Join-Path $root '.test-work\standalone-updater\output') `
        -Filter '*.msi' -File | Select-Object -First 1
    Assert-True ($null -ne $msi -and $msi.Length -gt 0) 'Updater MSI was not generated'
    $msiLog = Join-Path $work 'updater-msi.log'
    $installedRoot = Join-Path $env:ProgramFiles 'NicoCache_nl Updater'
    try {
        Invoke-MsiExec -Arguments @('/i', $msi.FullName, '/qn', '/norestart', '/l*v', $msiLog) `
            -FailureMessage 'Updater MSI install failed'
        $installedExe = Join-Path $installedRoot 'NicoCache_nl Updater.exe'
        Assert-File $installedExe
        Assert-File (Join-Path $installedRoot 'runtime\lib\modules')
        Assert-PackagedEngine $installedRoot
        Invoke-DependencyE2E $installedExe $installedRoot (Join-Path $work 'installed-target')
    }
    finally {
        Invoke-MsiExec -Arguments @('/x', $msi.FullName, '/qn', '/norestart', '/l*v',
                (Join-Path $work 'updater-msi-uninstall.log')) `
            -FailureMessage 'Updater MSI uninstall failed'
    }
    Assert-True (-not (Test-Path -LiteralPath $installedRoot)) `
        'Updater MSI left its install directory behind'
}
Write-Output 'Standalone updater functional E2E tests passed'
