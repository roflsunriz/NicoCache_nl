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
        [int]$TimeoutSeconds = 240) {
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

    $check = Invoke-UpdaterCli $Executable @(
        '--dependency-check', '--app-root', $TargetRoot, '--java-major', '21')
    foreach ($name in @('Eclipse Temurin OpenJDK', 'FFmpeg', 'Bouncy Castle', 'Apache Ant', '7-Zip')) {
        Assert-True $check.Output.Contains($name) "Dependency check output missing: $name`n$($check.Output)"
    }
    Assert-True (-not $check.Output.Contains('PowerShell')) 'Dependency check unexpectedly invoked PowerShell'
    Assert-Directory (Join-Path $TargetRoot '.runtime-dependency-updater')
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $UpdaterRoot '.runtime-dependency-updater'))) `
        'Dependency engine wrote state into the updater installation'
}

# Compile every production class plus dependency-free unit tests.
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

# Source invariants: standalone dependency handling is Java-only.
$updaterSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\NicoCacheUpdater.java') -Raw
$engineSource = Get-Content (Join-Path $root 'updater\src\dareka\updater\DependencyEngine.java') -Raw
foreach ($required in @(
        'tabs.addTab("NicoCache_nl"',
        'tabs.addTab("外部依存関係"',
        'new DependencyEngine(applicationRoot)',
        '--dependency-check',
        'engine=java')) {
    Assert-True $updaterSource.Contains($required) "Updater source invariant missing: $required"
}
foreach ($required in @(
        'resolveTemurin', 'resolveFfmpeg', 'resolveBouncyCastle', 'resolveAnt',
        'resolveSevenZip', 'transactionalReplace', 'assertInside', 'MAX_EXPANDED_BYTES')) {
    Assert-True $engineSource.Contains($required) "Java engine invariant missing: $required"
}
Assert-True (-not $updaterSource.Contains('powershell.exe')) 'GUI still invokes PowerShell'
Assert-True (-not $updaterSource.Contains('.ps1')) 'GUI still references a PowerShell script'
Assert-True (-not $engineSource.Contains('powershell.exe')) 'Java engine invokes PowerShell'
Assert-True (-not $engineSource.Contains('.ps1')) 'Java engine references a PowerShell script'

# Legacy exit-hook script remains covered but is not part of the standalone updater package.
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

# GUI startup smoke.
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
Write-Output 'Standalone updater pure Java functional E2E tests passed'
