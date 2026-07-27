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
        [int]$TimeoutSeconds = 300) {
    $stdout = Join-Path $work ('stdout-' + [guid]::NewGuid().ToString('N') + '.txt')
    $stderr = Join-Path $work ('stderr-' + [guid]::NewGuid().ToString('N') + '.txt')
    $argumentLine = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
    $process = Start-Process -FilePath $Executable -ArgumentList $argumentLine `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Updater CLI timed out: $Executable $argumentLine"
    }
    $stdoutText = if (Test-Path -LiteralPath $stdout) { Get-Content $stdout -Raw } else { '' }
    $stderrText = if (Test-Path -LiteralPath $stderr) { Get-Content $stderr -Raw } else { '' }
    $output = $stdoutText + $stderrText
    if ($process.ExitCode -ne 0) {
        throw "Updater CLI failed ($($process.ExitCode)): $argumentLine`n$output"
    }
    [pscustomobject]@{ ExitCode=$process.ExitCode; Output=$output }
}
function Assert-NoPowerShellPayload([string]$UpdaterRoot) {
    $files = @(Get-ChildItem -LiteralPath $UpdaterRoot -Recurse -File |
        Where-Object Extension -in @('.ps1', '.psd1', '.psm1'))
    if ($files.Count -ne 0) {
        $fileNames = @($files | ForEach-Object FullName) -join ', '
        throw "PowerShell payload leaked into standalone updater: $fileNames"
    }
}
function Invoke-PureJavaDependencyE2E([string]$Executable, [string]$UpdaterRoot, [string]$TargetRoot) {
    Remove-Item -LiteralPath $TargetRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $TargetRoot | Out-Null
    Set-Content (Join-Path $TargetRoot 'version.txt') 'broken-version' -Encoding ascii

    $self = Invoke-UpdaterCli $Executable @('--self-test', '--app-root', $TargetRoot)
    Assert-True $self.Output.Contains('SELF_TEST_OK') 'Pure Java self-test did not report success'
    Assert-True $self.Output.Contains('engine=java') 'Updater did not use the Java dependency engine'
    Assert-True $self.Output.Contains('TRANSACTION_E2E_OK') `
        "Packaged transaction/hash/zip-slip/rollback E2E did not run:`n$($self.Output)"

    $check = Invoke-UpdaterCli $Executable @(
        '--dependency-check', '--app-root', $TargetRoot, '--java-major', '21')
    foreach ($name in @('Eclipse Temurin OpenJDK', 'FFmpeg', 'Bouncy Castle', 'Apache Ant', '7-Zip')) {
        Assert-True $check.Output.Contains($name) "Dependency check output missing: $name`n$($check.Output)"
    }
    Assert-True $check.Output.Contains('検証情報あり') 'A provider resolved without verifiable hash metadata'
    Assert-True (-not $check.Output.Contains('PowerShell')) 'Dependency check unexpectedly invoked PowerShell'
    Assert-Directory (Join-Path $TargetRoot '.runtime-dependency-updater')
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $UpdaterRoot '.runtime-dependency-updater'))) `
        'Dependency engine wrote state into the updater installation'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $TargetRoot 'tools\selftest'))) `
        'Packaged self-test left test payload in the target installation'
}

# Compile every production class plus dependency-free unit/security tests.
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'updater\src') -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)
$tests = @(Get-ChildItem -LiteralPath (Join-Path $root 'updater\test') -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -Xlint:all -d $classes @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Updater javac/unit-test compilation failed' }
& java -cp $classes dareka.updater.NicoCacheUpdaterTest
if ($LASTEXITCODE -ne 0) { throw 'Updater Java unit tests failed' }
& java -cp $classes dareka.updater.DependencyEngineTest
if ($LASTEXITCODE -ne 0) { throw 'Dependency engine security/transaction tests failed' }

# Source invariants: standalone dependency handling is Java-only and all entry points are packaged.
$updaterSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') -Raw
$engineSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\DependencyEngine.java') -Raw
$launcherSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\UpdaterLauncher.java') -Raw
foreach ($required in @('tabs.addTab("NicoCache_nl"', 'tabs.addTab("外部依存関係"',
        'new DependencyEngine(applicationRoot)')) {
    Assert-True $updaterSource.Contains($required) "Updater source invariant missing: $required"
}
foreach ($required in @(
        'resolveTemurin', 'resolveFfmpeg', 'resolveBouncyCastle', 'resolveAnt',
        'resolveSevenZip', 'transactionalReplace', 'assertInside', 'assertNoReparseEscape',
        'MAX_EXPANDED_BYTES', 'selfTestTransactions', 'JSON_DIGEST', 'acquireOperationLock')) {
    Assert-True $engineSource.Contains($required) "Java engine invariant missing: $required"
}
foreach ($required in @('--self-test', '--dependency-check', '--dependency-update',
        'selfTestTransactions', 'engine=java')) {
    Assert-True $launcherSource.Contains($required) "Packaged launcher invariant missing: $required"
}
foreach ($sourceText in @($updaterSource, $engineSource, $launcherSource)) {
    Assert-True (-not $sourceText.Contains('powershell.exe')) 'Standalone updater still invokes PowerShell'
    Assert-True (-not $sourceText.Contains('.ps1')) 'Standalone updater still references a PowerShell script'
}

# Build and execute AppImage functional E2E.
$packageType = if ($BuildMsi) { 'All' } else { 'AppImage' }
& (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') `
    -PackageType $packageType -AppVersion 0.1.0
$appImage = Join-Path $root '.test-work\standalone-updater\output\NicoCache_nl Updater'
Assert-Directory $appImage
$appImageExe = Join-Path $appImage 'NicoCache_nl Updater.exe'
Assert-File $appImageExe
Assert-File (Join-Path $appImage 'runtime\lib\modules')
Assert-File (Join-Path $appImage 'app\NicoCacheUpdater.jar')
Assert-NoPowerShellPayload $appImage
Invoke-PureJavaDependencyE2E $appImageExe $appImage (Join-Path $work 'appimage-target')

# GUI startup smoke from the same packaged launcher.
$guiTarget = Join-Path $work 'gui-target'
New-Item -ItemType Directory -Path $guiTarget | Out-Null
$process = Start-Process -FilePath $appImageExe `
    -ArgumentList ('--app-root "' + $guiTarget + '"') -PassThru
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
        Assert-NoPowerShellPayload $installedRoot
        Invoke-PureJavaDependencyE2E $installedExe $installedRoot (Join-Path $work 'installed-target')
    }
    finally {
        Invoke-MsiExec -Arguments @('/x', $msi.FullName, '/qn', '/norestart', '/l*v',
                (Join-Path $work 'updater-msi-uninstall.log')) `
            -FailureMessage 'Updater MSI uninstall failed'
    }
    Assert-True (-not (Test-Path -LiteralPath $installedRoot)) `
        'Updater MSI left its install directory behind'
}
Write-Output 'Standalone updater pure Java unit, security, transaction and packaged E2E tests passed'
