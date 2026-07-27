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
        [int]$TimeoutSeconds = 300,
        [hashtable]$Environment = @{}) {
    $stdout = Join-Path $work ('stdout-' + [guid]::NewGuid().ToString('N') + '.txt')
    $stderr = Join-Path $work ('stderr-' + [guid]::NewGuid().ToString('N') + '.txt')
    $argumentLine = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
    $parameters = @{
        FilePath = $Executable
        ArgumentList = $argumentLine
        RedirectStandardOutput = $stdout
        RedirectStandardError = $stderr
        PassThru = $true
    }
    if ($Environment.Count -gt 0) { $parameters.Environment = $Environment }
    $process = Start-Process @parameters
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
function Assert-ExecutableHasIcon([string]$Executable) {
    Add-Type -AssemblyName System.Drawing
    $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($Executable)
    try {
        Assert-True ($null -ne $icon -and $icon.Width -ge 16 -and $icon.Height -ge 16) `
            "Packaged executable has no application icon: $Executable"
    }
    finally {
        if ($icon) { $icon.Dispose() }
    }
}
function Get-MsiProperty([string]$MsiPath, [string]$Property) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null,
        $installer, @($MsiPath, 0))
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null,
        $database, @("SELECT `Value` FROM `Property` WHERE `Property`='$Property'"))
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
    if ($null -eq $record) { return $null }
    $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1)
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

    $validated = Invoke-UpdaterCli $Executable @('--validate-target-root', '--app-root', $TargetRoot)
    Assert-True $validated.Output.Contains($TargetRoot) 'Packaged target validation did not use requested root'

    $check = Invoke-UpdaterCli $Executable @(
        '--dependency-check', '--app-root', $TargetRoot, '--java-major', '21')
    foreach ($name in @('Eclipse Temurin OpenJDK', 'FFmpeg', 'Bouncy Castle', 'Apache Ant', '7-Zip')) {
        Assert-True $check.Output.Contains($name) "Dependency check output missing: $name`n$($check.Output)"
    }
    Assert-True ($check.ExitCode -eq 0) 'Dependency provider verification did not complete successfully'
    Assert-True (-not $check.Output.Contains('PowerShell')) 'Dependency check unexpectedly invoked PowerShell'
    Assert-Directory (Join-Path $TargetRoot '.runtime-dependency-updater')
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $UpdaterRoot '.runtime-dependency-updater'))) `
        'Dependency engine wrote state into the updater installation'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $TargetRoot 'tools\selftest'))) `
        'Packaged self-test left test payload in the target installation'
}

$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'updater\src') -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)
$tests = @(Get-ChildItem -LiteralPath (Join-Path $root 'updater\test') -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -Xlint:all -d $classes @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Updater javac/unit-test compilation failed' }
foreach ($testClass in @(
        'dareka.updater.NicoCacheUpdaterTest',
        'dareka.updater.TargetRootResolverTest',
        'dareka.updater.DependencyEngineTest')) {
    & java -cp $classes $testClass
    if ($LASTEXITCODE -ne 0) { throw "Updater Java test failed: $testClass" }
}

$updaterSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') -Raw
$engineSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\DependencyEngine.java') -Raw
$launcherSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\UpdaterLauncher.java') -Raw
$resolverSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\TargetRootResolver.java') -Raw
$buildSource = Get-Content (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -Raw
foreach ($required in @('tabs.addTab("NicoCache_nl"', 'tabs.addTab("外部依存関係"',
        'changeTargetButton', 'JFileChooser', 'TargetRootResolver.remember',
        'new DependencyEngine(applicationRoot)')) {
    Assert-True $updaterSource.Contains($required) "Updater source invariant missing: $required"
}
foreach ($required in @('LOCALAPPDATA', 'NicoCache_nl.jar', 'NicoCache_nl.exe', 'Preferences')) {
    Assert-True $resolverSource.Contains($required) "Target resolver invariant missing: $required"
}
foreach ($required in @(
        'resolveTemurin', 'resolveFfmpeg', 'resolveBouncyCastle', 'resolveAnt',
        'resolveSevenZip', 'transactionalReplace', 'assertInside', 'assertNoReparseEscape',
        'MAX_EXPANDED_BYTES', 'selfTestTransactions', 'JSON_DIGEST', 'acquireOperationLock')) {
    Assert-True $engineSource.Contains($required) "Java engine invariant missing: $required"
}
foreach ($required in @('--self-test', '--dependency-check', '--dependency-update',
        '--print-target-root', '--validate-target-root', 'TargetRootResolver.resolve',
        'selfTestTransactions', 'engine=java')) {
    Assert-True $launcherSource.Contains($required) "Packaged launcher invariant missing: $required"
}
foreach ($required in @('-J-Duser.language=ja', '-J-Duser.country=JP', '--icon')) {
    Assert-True $buildSource.Contains($required) "Localized/icon packaging invariant missing: $required"
}
foreach ($sourceText in @($updaterSource, $engineSource, $launcherSource, $resolverSource)) {
    Assert-True (-not $sourceText.Contains('powershell.exe')) 'Standalone updater still invokes PowerShell'
    Assert-True (-not $sourceText.Contains('.ps1')) 'Standalone updater still references a PowerShell script'
}

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
Assert-ExecutableHasIcon $appImageExe
Invoke-PureJavaDependencyE2E $appImageExe $appImage (Join-Path $work 'appimage-target')

$isolatedLocalAppData = Join-Path $work 'isolated-localappdata'
New-Item -ItemType Directory -Path $isolatedLocalAppData | Out-Null
$defaultRoot = Invoke-UpdaterCli $appImageExe @('--print-target-root') 60 `
    @{ LOCALAPPDATA = $isolatedLocalAppData }
$expectedDefault = Join-Path $isolatedLocalAppData 'NicoCache_nl'
Assert-True ($defaultRoot.Output.Trim() -eq $expectedDefault) `
    "Packaged default target is not isolated LOCALAPPDATA: $($defaultRoot.Output)"

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
    Assert-True ((Get-MsiProperty $msi.FullName 'ProductLanguage') -eq '1041') `
        'Updater MSI ProductLanguage is not Japanese (1041)'
    $msiLog = Join-Path $work 'updater-msi.log'
    $installedRoot = Join-Path $env:ProgramFiles 'NicoCache_nl Updater'
    try {
        Invoke-MsiExec -Arguments @('/i', $msi.FullName, '/qn', '/norestart', '/l*v', $msiLog) `
            -FailureMessage 'Updater MSI install failed'
        $installedExe = Join-Path $installedRoot 'NicoCache_nl Updater.exe'
        Assert-File $installedExe
        Assert-File (Join-Path $installedRoot 'runtime\lib\modules')
        Assert-NoPowerShellPayload $installedRoot
        Assert-ExecutableHasIcon $installedExe
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
Write-Output 'Standalone updater localization, target-root, icon, Java, security and packaged E2E tests passed'
