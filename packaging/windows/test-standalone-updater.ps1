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
function Invoke-PackagedE2E([string]$Executable, [string]$UpdaterRoot, [string]$TargetRoot) {
    Remove-Item $TargetRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path (Join-Path $TargetRoot 'app') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $TargetRoot 'app\.jpackage.xml') -Encoding utf8 -Value `
        '<?xml version="1.0"?><jpackage-state><app-version>1.0.1</app-version><main-launcher>NicoCache_nl</main-launcher></jpackage-state>'

    $version = Invoke-UpdaterCli $Executable @('--installed-version', '--app-root', $TargetRoot)
    Assert-True ($version.Output.Trim() -eq '1.0.1') "Installed jpackage version was not detected: $($version.Output)"

    $self = Invoke-UpdaterCli $Executable @('--self-test', '--app-root', $TargetRoot)
    foreach ($marker in @('SELF_TEST_OK', 'SYSTEM_DEPENDENCY_SELF_TEST_OK', 'winget-first', 'fallback')) {
        Assert-True $self.Output.Contains($marker) "Packaged self-test missing: $marker"
    }

    $check = Invoke-UpdaterCli $Executable @('--dependency-check', '--app-root', $TargetRoot, '--java-major', '21') 600
    foreach ($name in @('Eclipse Temurin JDK', 'FFmpeg', 'Bouncy Castle', 'Apache Ant', '7-Zip', 'WinGet')) {
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
        'dareka.updater.DependencyEngineTest')) {
    & java -cp $classes $testClass
    if ($LASTEXITCODE -ne 0) { throw "Updater Java test failed: $testClass" }
}

$updaterSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') -Raw
$engineSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\DependencyEngine.java') -Raw
$systemSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\SystemDependencyManager.java') -Raw
$launcherSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\UpdaterLauncher.java') -Raw
$buildSource = Get-Content (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -Raw
foreach ($required in @('WinGetを優先', '現在のWindowsユーザー', 'ユーザーPATH', 'Bouncy Castleだけ')) {
    Assert-True $updaterSource.Contains($required) "Updater UI invariant missing: $required"
}
foreach ($required in @('EclipseAdoptium.Temurin.', 'Gyan.FFmpeg', 'Apache.Ant', '7zip.7zip',
        '--scope', 'user', 'resolveTemurin', 'resolveFfmpeg', 'resolveAnt', 'resolveSevenZip',
        'HKCU\\Environment', 'JAVA_HOME', 'mergePath', 'verifyExecutable')) {
    Assert-True $systemSource.Contains($required) "System dependency invariant missing: $required"
}
foreach ($required in @('resolveBouncyCastle', 'NicoCache_nl専用')) {
    Assert-True $engineSource.Contains($required) "Bouncy Castle invariant missing: $required"
}
Assert-True (-not $launcherSource.Contains('ApplicationProcessGuard.requireStopped(applicationRoot);`n                DependencyEngine')) `
    'Dependency update still requires NicoCache_nl to be stopped'
foreach ($required in @('-J-Duser.language=ja', '-J-Duser.country=JP', '--icon')) {
    Assert-True $buildSource.Contains($required) "Packaging invariant missing: $required"
}
foreach ($sourceText in @($updaterSource, $engineSource, $systemSource, $launcherSource)) {
    Assert-True (-not $sourceText.Contains('powershell.exe')) 'Updater invokes PowerShell'
    Assert-True (-not $sourceText.Contains('.ps1')) 'Updater references PowerShell payload'
}

$packageType = if ($BuildMsi) { 'All' } else { 'AppImage' }
& (Join-Path $root 'packaging\windows\build-standalone-updater.ps1') -PackageType $packageType -AppVersion 0.1.0
$appImage = Join-Path $root '.test-work\standalone-updater\output\NicoCache_nl Updater'
$appImageExe = Join-Path $appImage 'NicoCache_nl Updater.exe'
Assert-File $appImageExe
Assert-File (Join-Path $appImage 'runtime\lib\modules')
Assert-File (Join-Path $appImage 'app\NicoCacheUpdater.jar')
Assert-NoPowerShellPayload $appImage
Assert-ExecutableHasIcon $appImageExe
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
    Assert-True $jpackageLog.Contains('-cultures:ja-JP') 'Japanese MSI culture was not selected'
    $installedRoot = Join-Path $env:ProgramFiles 'NicoCache_nl Updater'
    try {
        Invoke-MsiExec @('/i', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi.log')) 'Updater MSI install failed'
        $installedExe = Join-Path $installedRoot 'NicoCache_nl Updater.exe'
        Assert-File $installedExe
        Assert-NoPowerShellPayload $installedRoot
        Assert-ExecutableHasIcon $installedExe
        Invoke-PackagedE2E $installedExe $installedRoot (Join-Path $work 'installed-target')
    }
    finally {
        Invoke-MsiExec @('/x', $msi.FullName, '/qn', '/norestart', '/l*v', (Join-Path $work 'updater-msi-uninstall.log')) 'Updater MSI uninstall failed'
    }
    Assert-True (-not (Test-Path $installedRoot)) 'Updater MSI left its install directory behind'
}
Write-Output 'Standalone updater winget-first, fallback, localization, icon and packaged E2E tests passed'
