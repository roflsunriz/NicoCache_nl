#Requires -Version 7.0
[CmdletBinding()]
param([switch]$BuildMsi)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = Join-Path $root '.test-work\updater-tests'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-File([string]$Path) { Assert-True (Test-Path -LiteralPath $Path -PathType Leaf) "File missing: $Path" }
function Assert-Directory([string]$Path) { Assert-True (Test-Path -LiteralPath $Path -PathType Container) "Directory missing: $Path" }
function Invoke-MsiExec([string[]]$Arguments, [string]$FailureMessage) {
    $quoted = $Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ } }
    $process = Start-Process msiexec.exe -ArgumentList ($quoted -join ' ') -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) { throw "$FailureMessage (ExitCode: $($process.ExitCode))" }
}
function Invoke-UpdaterCliRaw([string]$Executable, [string[]]$Arguments,
        [int]$TimeoutSeconds = 300, [hashtable]$Environment = @{}) {
    $stdout = Join-Path $work ('stdout-' + [guid]::NewGuid().ToString('N') + '.txt')
    $stderr = Join-Path $work ('stderr-' + [guid]::NewGuid().ToString('N') + '.txt')
    $argumentLine = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ } }) -join ' '
    $parameters = @{ FilePath=$Executable; ArgumentList=$argumentLine; RedirectStandardOutput=$stdout; RedirectStandardError=$stderr; PassThru=$true }
    if ($Environment.Count -gt 0) { $parameters.Environment = $Environment }
    $process = Start-Process @parameters
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Updater CLI timed out: $Executable $argumentLine"
    }
    $stdoutText = if (Test-Path $stdout) { Get-Content $stdout -Raw } else { '' }
    $stderrText = if (Test-Path $stderr) { Get-Content $stderr -Raw } else { '' }
    [pscustomobject]@{ ExitCode=$process.ExitCode; Output=($stdoutText + $stderrText); Arguments=$argumentLine }
}
function Invoke-UpdaterCli([string]$Executable, [string[]]$Arguments,
        [int]$TimeoutSeconds = 300, [hashtable]$Environment = @{}) {
    $result = Invoke-UpdaterCliRaw $Executable $Arguments $TimeoutSeconds $Environment
    if ($result.ExitCode -ne 0) { throw "Updater CLI failed ($($result.ExitCode)): $($result.Arguments)`n$($result.Output)" }
    $result
}
function Invoke-UpdaterCliExpectFailure([string]$Executable, [string[]]$Arguments,
        [string]$ExpectedText) {
    $result = Invoke-UpdaterCliRaw $Executable $Arguments
    Assert-True ($result.ExitCode -ne 0) "Updater CLI unexpectedly succeeded: $($result.Arguments)"
    Assert-True $result.Output.Contains($ExpectedText) "Expected failure text missing: $ExpectedText`n$($result.Output)"
    $result
}
function Assert-NoPowerShellPayload([string]$UpdaterRoot) {
    $files = @(Get-ChildItem -LiteralPath $UpdaterRoot -Recurse -File | Where-Object Extension -in @('.ps1', '.psd1', '.psm1'))
    if ($files.Count -ne 0) { throw "PowerShell payload leaked into standalone updater: $(@($files.FullName) -join ', ')" }
}
function Assert-ExecutableHasIcon([string]$Executable) {
    Add-Type -AssemblyName System.Drawing
    $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($Executable)
    try { Assert-True ($null -ne $icon -and $icon.Width -ge 16) "Packaged executable has no icon: $Executable" }
    finally { if ($icon) { $icon.Dispose() } }
}
function Invoke-PureJavaDependencyE2E([string]$Executable, [string]$UpdaterRoot, [string]$TargetRoot) {
    Remove-Item $TargetRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path (Join-Path $TargetRoot 'app') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $TargetRoot 'app\.jpackage.xml') -Encoding utf8 -Value `
        '<?xml version="1.0"?><jpackage-state><app-version>1.0.1</app-version><main-launcher>NicoCache_nl</main-launcher></jpackage-state>'

    $version = Invoke-UpdaterCli $Executable @('--installed-version', '--app-root', $TargetRoot)
    Assert-True ($version.Output.Trim() -eq '1.0.1') "Installed jpackage version was not detected: $($version.Output)"
    Assert-True ((Get-Content (Join-Path $TargetRoot 'version.txt') -Raw).Trim() -eq '1.0.1') `
        'Resolved jpackage version was not materialized for subsequent reads'

    $self = Invoke-UpdaterCli $Executable @('--self-test', '--app-root', $TargetRoot)
    foreach ($marker in @('SELF_TEST_OK', 'engine=java', 'TRANSACTION_E2E_OK')) {
        Assert-True $self.Output.Contains($marker) "Packaged self-test missing: $marker"
    }
    $validated = Invoke-UpdaterCli $Executable @('--validate-target-root', '--app-root', $TargetRoot)
    Assert-True $validated.Output.Contains($TargetRoot) 'Target validation did not use requested root'
    $check = Invoke-UpdaterCli $Executable @('--dependency-check', '--app-root', $TargetRoot, '--java-major', '21')
    foreach ($name in @('Eclipse Temurin OpenJDK', 'FFmpeg', 'Bouncy Castle', 'Apache Ant', '7-Zip')) {
        Assert-True $check.Output.Contains($name) "Dependency check output missing: $name"
    }

    $fakeExe = Join-Path $TargetRoot 'NicoCache_nl.exe'
    Copy-Item -LiteralPath (Join-Path $env:SystemRoot 'System32\cmd.exe') -Destination $fakeExe
    $targetProcess = Start-Process -FilePath $fakeExe -ArgumentList '/c ping -n 30 127.0.0.1 >nul' -PassThru
    try {
        Start-Sleep -Milliseconds 750
        Invoke-UpdaterCliExpectFailure $Executable @(
            '--assert-application-stopped', '--app-root', $TargetRoot) 'NicoCache_nlが実行中' | Out-Null
        Invoke-UpdaterCliExpectFailure $Executable @(
            '--dependency-update', '--app-root', $TargetRoot, '--java-major', '21') 'NicoCache_nlが実行中' | Out-Null
    }
    finally {
        Stop-Process -Id $targetProcess.Id -Force -ErrorAction SilentlyContinue
        $targetProcess.WaitForExit()
    }
    $stopped = Invoke-UpdaterCli $Executable @('--assert-application-stopped', '--app-root', $TargetRoot)
    Assert-True $stopped.Output.Contains('APPLICATION_STOPPED') 'Stopped application was still reported as running'

    Assert-Directory (Join-Path $TargetRoot '.runtime-dependency-updater')
    Assert-True (-not (Test-Path (Join-Path $UpdaterRoot '.runtime-dependency-updater'))) 'State leaked into updater installation'
    Assert-True (-not (Test-Path (Join-Path $TargetRoot 'tools\selftest'))) 'Self-test payload leaked'
}

$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'updater\src') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
$tests = @(Get-ChildItem (Join-Path $root 'updater\test') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -Xlint:all -d $classes @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Updater compilation failed' }
foreach ($testClass in @(
        'dareka.updater.NicoCacheUpdaterTest',
        'dareka.updater.TargetRootResolverTest',
        'dareka.updater.InstalledVersionDetectorTest',
        'dareka.updater.ApplicationProcessGuardTest',
        'dareka.updater.DependencyEngineTest')) {
    & java -cp $classes $testClass
    if ($LASTEXITCODE -ne 0) { throw "Updater Java test failed: $testClass" }
}

$updaterSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') -Raw
$engineSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\DependencyEngine.java') -Raw
$launcherSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\UpdaterLauncher.java') -Raw
$resolverSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\TargetRootResolver.java') -Raw
$versionSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\InstalledVersionDetector.java') -Raw
$guardSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\ApplicationProcessGuard.java') -Raw
$buildSource = Get-Content (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -Raw
foreach ($required in @('tabs.addTab("NicoCache_nl"', 'tabs.addTab("外部依存関係"', 'changeTargetButton', 'JFileChooser', 'TargetRootResolver.remember')) {
    Assert-True $updaterSource.Contains($required) "Updater invariant missing: $required"
}
foreach ($required in @('LOCALAPPDATA', 'NicoCache_nl.jar', 'NicoCache_nl.exe', 'Preferences', 'InstalledVersionDetector.detect')) {
    Assert-True $resolverSource.Contains($required) "Target resolver invariant missing: $required"
}
foreach ($required in @('<app-version>', '.jpackage.xml', 'version.txt')) {
    Assert-True $versionSource.Contains($required) "Installed version detector invariant missing: $required"
}
foreach ($required in @('ProcessHandle.allProcesses', 'NicoCache_nlが実行中', 'startsWith(normalizedRoot)')) {
    Assert-True $guardSource.Contains($required) "Process guard invariant missing: $required"
}
foreach ($required in @('resolveTemurin', 'resolveFfmpeg', 'resolveBouncyCastle', 'resolveAnt', 'resolveSevenZip', 'transactionalReplace', 'assertInside', 'assertNoReparseEscape', 'MAX_EXPANDED_BYTES', 'selfTestTransactions', 'JSON_DIGEST', 'acquireOperationLock')) {
    Assert-True $engineSource.Contains($required) "Java engine invariant missing: $required"
}
foreach ($required in @('--self-test', '--dependency-check', '--dependency-update', '--installed-version', '--assert-application-stopped', 'ApplicationProcessGuard.requireStopped')) {
    Assert-True $launcherSource.Contains($required) "Launcher invariant missing: $required"
}
foreach ($required in @('-J-Duser.language=ja', '-J-Duser.country=JP', '--icon')) {
    Assert-True $buildSource.Contains($required) "Packaging invariant missing: $required"
}
foreach ($sourceText in @($updaterSource, $engineSource, $launcherSource, $resolverSource, $versionSource, $guardSource)) {
    Assert-True (-not $sourceText.Contains('powershell.exe')) 'Updater invokes PowerShell'
    Assert-True (-not $sourceText.Contains('.ps1')) 'Updater references PowerShell payload'
}

$packageType = if ($BuildMsi) { 'All' } else { 'AppImage' }
& (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -PackageType $packageType -AppVersion 0.1.0
$appImage = Join-Path $root '.test-work\standalone-updater\output\NicoCache_nl Updater'
$appImageExe = Join-Path $appImage 'NicoCache_nl Updater.exe'
Assert-Directory $appImage
Assert-File $appImageExe
Assert-File (Join-Path $appImage 'runtime\lib\modules')
Assert-File (Join-Path $appImage 'app\NicoCacheUpdater.jar')
Assert-NoPowerShellPayload $appImage
Assert-ExecutableHasIcon $appImageExe
Invoke-PureJavaDependencyE2E $appImageExe $appImage (Join-Path $work 'appimage-target')

$isolatedLocalAppData = Join-Path $work 'isolated-localappdata'
New-Item -ItemType Directory -Path $isolatedLocalAppData | Out-Null
$defaultRoot = Invoke-UpdaterCli $appImageExe @('--print-target-root') 60 @{ LOCALAPPDATA=$isolatedLocalAppData }
Assert-True ($defaultRoot.Output.Trim() -eq (Join-Path $isolatedLocalAppData 'NicoCache_nl')) 'Default target is not isolated LOCALAPPDATA'

$guiTarget = Join-Path $work 'gui-target'
New-Item -ItemType Directory -Path $guiTarget | Out-Null
$process = Start-Process $appImageExe -ArgumentList ('--app-root "' + $guiTarget + '"') -PassThru
Start-Sleep 5
Assert-True (-not $process.HasExited) 'Updater GUI did not remain running'
Stop-Process -Id $process.Id -Force

if ($BuildMsi) {
    $msi = Get-ChildItem (Join-Path $root '.test-work\standalone-updater\output') -Filter '*.msi' -File | Select-Object -First 1
    Assert-True ($null -ne $msi -and $msi.Length -gt 0) 'Updater MSI was not generated'
    $jpackageLog = Get-Content (Join-Path $root '.test-work\standalone-updater\jpackage.log') -Raw
    Assert-True $jpackageLog.Contains('MsiInstallerStrings_ja.wxl') 'Japanese MSI localization resource was not linked'
    Assert-True $jpackageLog.Contains('-cultures:ja-JP') 'Japanese MSI culture was not selected'
    $installedRoot = Join-Path $env:ProgramFiles 'NicoCache_nl Updater'
    try {
        Invoke-MsiExec @('/i', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi.log')) 'Updater MSI install failed'
        $installedExe = Join-Path $installedRoot 'NicoCache_nl Updater.exe'
        Assert-File $installedExe
        Assert-NoPowerShellPayload $installedRoot
        Assert-ExecutableHasIcon $installedExe
        Invoke-PureJavaDependencyE2E $installedExe $installedRoot (Join-Path $work 'installed-target')
    }
    finally {
        Invoke-MsiExec @('/x', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi-uninstall.log')) 'Updater MSI uninstall failed'
    }
    Assert-True (-not (Test-Path $installedRoot)) 'Updater MSI left its install directory behind'
}
Write-Output 'Standalone updater real-version, running-process, localization, icon, Java, security and packaged E2E tests passed'
