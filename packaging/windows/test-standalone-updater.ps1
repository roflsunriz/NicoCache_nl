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
function Invoke-MsiExec([string[]]$Arguments, [string]$FailureMessage) {
    $quoted = $Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ } }
    $process = Start-Process msiexec.exe -ArgumentList ($quoted -join ' ') -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) { throw "$FailureMessage (ExitCode: $($process.ExitCode))" }
}
function Invoke-UpdaterCli([string]$Executable, [string[]]$Arguments, [int]$TimeoutSeconds = 300,
        [hashtable]$Environment = @{}) {
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
    $out = if (Test-Path $stdout) { Get-Content $stdout -Raw } else { '' }
    $err = if (Test-Path $stderr) { Get-Content $stderr -Raw } else { '' }
    if ($process.ExitCode -ne 0) { throw "Updater CLI failed ($($process.ExitCode)): $argumentLine`n$out$err" }
    [pscustomobject]@{ ExitCode=$process.ExitCode; Output=($out + $err) }
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
function Assert-ExecutableMetadata([string]$Executable) {
    $versionInfo = (Get-Item -LiteralPath $Executable).VersionInfo
    Assert-True ($versionInfo.ProductName -eq 'NicoCache_nl Updater') "Updater ProductName is incorrect: $Executable"
    Assert-True ($versionInfo.OriginalFilename -eq 'NicoCache_nl Updater.exe') "Updater OriginalFilename is incorrect: $Executable"
    Assert-True ($versionInfo.FileDescription -eq
        'NicoCache_nl updater for the application and external dependencies') "Updater FileDescription is incorrect: $Executable ($($versionInfo.FileDescription))"
}
function Invoke-PackagedE2E([string]$Executable, [string]$UpdaterRoot, [string]$TargetRoot) {
    Remove-Item $TargetRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path (Join-Path $TargetRoot 'app') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $TargetRoot 'app\NicoCache_nl.cfg') -Encoding utf8 -Value @'
[Application]
app.mainmodule=NicoCache_nl.jar

[JavaOptions]
java-options=-Djpackage.app-version=1.0.1
'@

    $version = Invoke-UpdaterCli $Executable @('--installed-version', '--app-root', $TargetRoot)
    Assert-True ($version.Output.Trim() -eq '1.0.1') "Installed launcher version was not detected: $($version.Output)"

    $applicationCheck = Invoke-UpdaterCli $Executable @('--headless', '--application-check', '--app-root', $TargetRoot) 600
    Assert-True $applicationCheck.Output.Contains('NicoCache_nl-') 'Headless application update check did not run'

    $self = Invoke-UpdaterCli $Executable @('--self-test', '--app-root', $TargetRoot)
    foreach ($marker in @('SELF_TEST_OK', 'SYSTEM_DEPENDENCY_SELF_TEST_OK', 'winget-source', 'winget-first', 'fallback')) {
        Assert-True $self.Output.Contains($marker) "Packaged self-test missing: $marker"
    }

    $check = Invoke-UpdaterCli $Executable @('--dependency-check', '--app-root', $TargetRoot, '--java-major', '25') 600
    foreach ($name in @('Eclipse Temurin JDK', 'FFmpeg', 'Bouncy Castle', 'Apache Ant', '7-Zip', 'GPAC / MP4Box', 'WinGet')) {
        Assert-True $check.Output.Contains($name) "Dependency check output missing: $name"
    }
    Assert-True (-not (Test-Path (Join-Path $TargetRoot 'runtime'))) 'System Temurin was written into NicoCache_nl'
    Assert-True (-not (Test-Path (Join-Path $TargetRoot 'tools\ffmpeg'))) 'System FFmpeg was written into NicoCache_nl'
    Assert-True (-not (Test-Path (Join-Path $UpdaterRoot '.runtime-dependency-updater'))) 'State leaked into updater installation'
}

$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'updater\src') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
$tests = @(Get-ChildItem (Join-Path $root 'updater\test') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -Xlint:all -d $classes @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Updater compilation failed' }
foreach ($testClass in @('dareka.updater.NicoCacheUpdaterTest', 'dareka.updater.TargetRootResolverTest',
        'dareka.updater.InstalledVersionDetectorTest', 'dareka.updater.ApplicationProcessGuardTest',
        'dareka.updater.DependencyEngineTest', 'dareka.updater.ArchiveApplicationInstallerTest',
        'dareka.updater.UpdaterPlatformTest', 'dareka.updater.DependencyStatusTest',
        'dareka.updater.WindowsDependencyManagerTest')) {
    & java -cp $classes $testClass
    if ($LASTEXITCODE -ne 0) { throw "Updater Java test failed: $testClass" }
}

$packageType = if ($BuildMsi) { 'All' } else { 'AppImage' }
& (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -PackageType $packageType -AppVersion 0.2.0
$appImage = Join-Path $root '.test-work\standalone-updater\output\NicoCache_nl Updater'
$appImageExe = Join-Path $appImage 'NicoCache_nl Updater.exe'
Assert-File $appImageExe
Assert-File (Join-Path $appImage 'runtime\lib\modules')
Assert-File (Join-Path $appImage 'app\NicoCacheUpdater.jar')
Assert-NoPowerShellPayload $appImage
Assert-ExecutableHasIcon $appImageExe
Assert-ExecutableMetadata $appImageExe
Invoke-PackagedE2E $appImageExe $appImage (Join-Path $work 'appimage-target')

$isolatedLocalAppData = Join-Path $work 'isolated-localappdata'
New-Item -ItemType Directory -Path $isolatedLocalAppData | Out-Null
$defaultRoot = Invoke-UpdaterCli $appImageExe @('--print-target-root') 60 @{ LOCALAPPDATA=$isolatedLocalAppData }
Assert-True ($defaultRoot.Output.Trim() -eq (Join-Path $isolatedLocalAppData 'NicoCache_nl')) 'Default target is not isolated LOCALAPPDATA'

if ($BuildMsi) {
    $msi = Get-ChildItem (Join-Path $root '.test-work\standalone-updater\output') -Filter '*.msi' -File | Select-Object -First 1
    Assert-True ($null -ne $msi -and $msi.Length -gt 0) 'Updater MSI was not generated'
    $jpackageLog = Get-Content (Join-Path $root '.test-work\standalone-updater\jpackage.log') -Raw
    Assert-True $jpackageLog.Contains('MsiInstallerStrings_ja.wxl') 'Japanese MSI localization resource was not linked'
    Assert-True (
        $jpackageLog.IndexOf('-cultures:ja-JP', [StringComparison]::OrdinalIgnoreCase) -ge 0
    ) 'Japanese MSI culture was not selected'
    Assert-True (-not $jpackageLog.Contains('-dJpIsSystemWide=yes')) 'Updater MSI unexpectedly requests a machine-wide install'
    # jpackage's --win-per-user-install uses LocalAppData directly. It does not
    # add the Programs segment used by the main application's launcher package.
    $installedRoot = Join-Path $env:LOCALAPPDATA 'NicoCache_nl Updater'
    try {
        Invoke-MsiExec @('/i', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi.log')) 'Updater MSI install failed'
        $installedExe = Join-Path $installedRoot 'NicoCache_nl Updater.exe'
        Assert-File $installedExe
        Assert-NoPowerShellPayload $installedRoot
        Assert-ExecutableHasIcon $installedExe
        Assert-ExecutableMetadata $installedExe
        Invoke-PackagedE2E $installedExe $installedRoot (Join-Path $work 'installed-target')
    }
    finally {
        Invoke-MsiExec @('/x', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi-uninstall.log')) 'Updater MSI uninstall failed'
    }
    Assert-True (-not (Test-Path $installedRoot)) 'Updater MSI left its install directory behind'
}
Write-Output 'Standalone updater package behavior tests passed'
