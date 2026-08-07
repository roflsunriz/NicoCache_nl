#Requires -Version 7.0
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'This test changes Windows installer state and may run only on GitHub Actions.' }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = Join-Path $root '.test-work\real-user-e2e'
New-Item -ItemType Directory -Force $work | Out-Null
$transcript = Join-Path $work 'transcript.txt'
$errorFile = Join-Path $work 'failure.txt'

function Step([string]$Message) {
    $line = "[$([DateTime]::UtcNow.ToString('O'))] $Message"
    Write-Host $line
    Add-Content -LiteralPath (Join-Path $work 'milestones.txt') -Value $line -Encoding utf8
}

function Invoke-Msi([string[]]$Arguments, [string]$Name, [string]$LogName) {
    $log = Join-Path $work $LogName
    $effective = @($Arguments) + @('/L*V', "`"$log`"")
    Step "${Name}: msiexec $($effective -join ' ')"
    $process = Start-Process msiexec.exe -ArgumentList $effective -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) { throw "$Name failed with exit code $($process.ExitCode)" }
}

function Get-MsiProperty([string]$Path, [string]$Property) {
    $installer = $null; $database = $null; $view = $null; $record = $null
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $database = $installer.GetType().InvokeMember('OpenDatabase', [Reflection.BindingFlags]::InvokeMethod, $null, $installer, @($Path, 0))
        $view = $database.GetType().InvokeMember('OpenView', [Reflection.BindingFlags]::InvokeMethod, $null, $database, @("SELECT `Value` FROM `Property` WHERE `Property` = '$Property'"))
        $view.GetType().InvokeMember('Execute', [Reflection.BindingFlags]::InvokeMethod, $null, $view, $null) | Out-Null
        $record = $view.GetType().InvokeMember('Fetch', [Reflection.BindingFlags]::InvokeMethod, $null, $view, $null)
        if (-not $record) { throw "MSI property is missing: $Property" }
        return $record.StringData(1)
    } finally {
        if ($record) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null }
        if ($view) { $view.GetType().InvokeMember('Close', [Reflection.BindingFlags]::InvokeMethod, $null, $view, $null) | Out-Null; [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null }
        if ($database) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null }
        if ($installer) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null }
    }
}

function Invoke-Updater([string]$Executable, [string[]]$Arguments, [string]$Name, [int]$TimeoutSeconds = 3600) {
    $stdout = Join-Path $work "$Name.stdout.txt"
    $stderr = Join-Path $work "$Name.stderr.txt"
    $line = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ } }) -join ' '
    Step "${Name}: $Executable $line"
    $process = Start-Process -FilePath $Executable -ArgumentList $line -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue; throw "$Name timed out" }
    $output = ((Get-Content $stdout -Raw -ErrorAction SilentlyContinue) + (Get-Content $stderr -Raw -ErrorAction SilentlyContinue))
    if ($process.ExitCode -ne 0) { throw "$Name failed ($($process.ExitCode))`n$output" }
    return $output
}

function Assert-FreshCommand([string]$Command, [string[]]$Arguments) {
    $cmdFile = Join-Path $work ("fresh-$Command.cmd")
    $items = @($Command) + @($Arguments)
    $commandLine = ($items | ForEach-Object { '"' + $_.Replace('"', '""') + '"' }) -join ' '
    Set-Content -LiteralPath $cmdFile -Encoding ascii -Value "@echo off`r`n$commandLine"
    $stdout = Join-Path $work ("fresh-$Command.stdout.txt")
    $stderr = Join-Path $work ("fresh-$Command.stderr.txt")
    $process = Start-Process cmd.exe -ArgumentList @('/d', '/c', "`"$cmdFile`"") -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Fresh CMD command failed: $commandLine`n$((Get-Content $stdout -Raw -ErrorAction SilentlyContinue) + (Get-Content $stderr -Raw -ErrorAction SilentlyContinue))" }
}

$updaterInstalled = $false
$productInstalled = $false
$productMsi = $null
$updaterMsi = $null
Start-Transcript -Path $transcript -Force
try {
    Step 'Resolve latest public NicoCache_nl MSI'
    $headers = @{ Authorization = "Bearer $env:GITHUB_TOKEN"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28'; 'User-Agent' = 'NicoCache_nl strict updater E2E' }
    $release = Invoke-RestMethod 'https://api.github.com/repos/roflsunriz/NicoCache_nl/releases/latest' -Headers $headers
    if ($release.tag_name -notmatch '^v(?<version>\d+(?:\.\d+){2})$') {
        throw "Unsupported latest release tag: $($release.tag_name)"
    }
    $tagVersion = $Matches.version
    $productMsiName = "NicoCache_nl-$tagVersion.msi"
    $assets = @($release.assets | Where-Object name -eq $productMsiName)
    if ($assets.Count -ne 1) { throw "Latest release product MSI is missing or duplicated: $productMsiName" }
    $asset = $assets[0]
    $productMsi = Join-Path $env:RUNNER_TEMP $asset.name
    Invoke-WebRequest $asset.browser_download_url -Headers $headers -OutFile $productMsi
    $productVersion = Get-MsiProperty $productMsi 'ProductVersion'
    Step "Release tag=$tagVersion MSI ProductVersion=$productVersion"
    if ($tagVersion -ne $productVersion) { throw "Release tag and MSI ProductVersion disagree: $tagVersion vs $productVersion" }

    $updaterMsi = Get-ChildItem (Join-Path $root '.test-work\standalone-updater\output') -Filter '*.msi' -File | Select-Object -First 1
    if (-not $updaterMsi) { throw 'Built updater MSI is missing' }
    $updaterRoot = Join-Path $env:LOCALAPPDATA 'NicoCache_nl Updater'

    Invoke-Msi @('/i', "`"$($updaterMsi.FullName)`"", '/qn', '/norestart') 'Install updater MSI' 'updater-install.log'
    $updaterInstalled = $true
    $updaterExe = Join-Path $updaterRoot 'NicoCache_nl Updater.exe'
    if (-not (Test-Path $updaterExe -PathType Leaf)) { throw "Installed updater executable missing: $updaterExe" }

    Invoke-Msi @('/i', "`"$productMsi`"", '/qn', '/norestart') 'Install NicoCache_nl MSI' 'product-install.log'
    $productInstalled = $true

    $installerState = Get-ItemProperty 'HKCU:\Software\NicoCache_nl\Installer' -ErrorAction Stop
    $productRoot = [IO.Path]::GetFullPath($installerState.InstallDir).TrimEnd('\')
    Step "Installed product root=$productRoot"
    $cfg = Join-Path $productRoot 'app\NicoCache_nl.cfg'
    if (-not (Test-Path $cfg -PathType Leaf)) { throw "Installed cfg missing: $cfg" }
    Copy-Item $cfg (Join-Path $work 'installed-NicoCache_nl.cfg')

    $detected = (Invoke-Updater $updaterExe @('--installed-version', '--app-root', $productRoot) 'version-detect').Trim()
    Step "Detected installed version=$detected"
    if ($detected -ne $productVersion) { throw "Installed version detection mismatch: expected=$productVersion actual=$detected" }

    $dependencyCheckOutput = Invoke-Updater $updaterExe @('--dependency-check', '--app-root', $productRoot, '--java-major', '25') 'dependency-check' 600
    foreach ($name in @('Eclipse Temurin JDK', 'FFmpeg', 'Apache Ant', '7-Zip', 'GPAC / MP4Box', 'Bouncy Castle')) {
        if (-not $dependencyCheckOutput.Contains($name)) { throw "Dependency check omitted: $name" }
    }
    foreach ($route in @('Eclipse Temurin JDK', 'FFmpeg', 'GPAC / MP4Box')) {
        $routeLines = @($dependencyCheckOutput -split '\r?\n' | Where-Object { $_ -match "^${route}:" })
        if ($routeLines.Count -ne 1) {
            throw "$route is available from WinGet but its own route was not reported exactly once`n$dependencyCheckOutput"
        }
        if ($routeLines[0] -notmatch 'WinGet') {
            throw "$route did not resolve through WinGet`n$dependencyCheckOutput"
        }
    }

    $dependencyOutput = Invoke-Updater $updaterExe @('--dependency-update', '--app-root', $productRoot, '--java-major', '25') 'dependency-update' 3600
    if ([string]::IsNullOrWhiteSpace($dependencyOutput)) {
        throw 'Dependency update returned no result'
    }
    foreach ($route in @('Eclipse Temurin JDK', 'FFmpeg', 'GPAC / MP4Box')) {
        $routeLines = @($dependencyOutput -split '\r?\n' | Where-Object { $_ -match "^${route}:" })
        foreach ($line in $routeLines) {
            if ($line -match '(?i)WinGet.*(?:fallback|フォールバック|不成立)') {
                throw "$route update entered fallback even though WinGet was available`n$dependencyOutput"
            }
        }
    }

    $dependencyAfter = Invoke-Updater $updaterExe @('--dependency-check', '--app-root', $productRoot, '--java-major', '25') 'dependency-check-after-update' 600
    $ffmpegLine = @($dependencyAfter -split '\r?\n' | Where-Object { $_ -match '^FFmpeg:' })
    if ($ffmpegLine.Count -ne 1 -or
            $ffmpegLine[0] -notmatch '導入版=(?<installed>\d+(?:\.\d+){1,3}), 最新版=(?<latest>\d+(?:\.\d+){1,3})' -or
            $Matches.installed -ne $Matches.latest) {
        throw "FFmpeg installed/latest versions do not agree after update`n$dependencyAfter"
    }
    $bouncyLine = @($dependencyAfter -split '\r?\n' | Where-Object { $_ -match '^Bouncy Castle:' })
    if ($bouncyLine.Count -ne 1 -or $bouncyLine[0] -match '導入版=不明|更新あり') {
        throw "Bouncy Castle was not detected from the application classpath after update`n$dependencyAfter"
    }

    $env:PATH = (([Environment]::GetEnvironmentVariable('Path', 'Machine'), [Environment]::GetEnvironmentVariable('Path', 'User')) | Where-Object { $_ }) -join ';'
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User'); if ($javaHome) { $env:JAVA_HOME = $javaHome }
    Assert-FreshCommand java @('-version'); Assert-FreshCommand javac @('-version'); Assert-FreshCommand ffmpeg @('-version'); Assert-FreshCommand ffprobe @('-version'); Assert-FreshCommand ant @('-version'); Assert-FreshCommand 7z @(); Assert-FreshCommand MP4Box @('-version')

    foreach ($command in @('java','javac','ffmpeg','ffprobe','ant','7z','MP4Box')) {
        $resolved = (Get-Command $command -ErrorAction Stop).Source
        Step "$command -> $resolved"
        if ([IO.Path]::GetFullPath($resolved).StartsWith($productRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "External dependency leaked into product root: $command -> $resolved" }
    }
    foreach ($jar in @('bcprov.jar','bcpkix.jar','bcutil.jar','brotli-dec.jar','zstd-jni.jar')) {
        if (-not (Test-Path (Join-Path $productRoot "app\lib\$jar") -PathType Leaf)) {
            throw "Dependency file missing from the application classpath: $jar"
        }
        if (Test-Path (Join-Path $productRoot "lib\$jar") -PathType Leaf) {
            throw "Dependency was written outside the application classpath: $jar"
        }
    }

    $disabledCfg = "$cfg.disabled"
    Move-Item $cfg $disabledCfg
    try {
        $negative = (Invoke-Updater $updaterExe @('--installed-version', '--app-root', $productRoot) 'version-negative-control').Trim()
        if ($negative -eq $productVersion) { throw 'Negative control failed: version remained detectable after cfg removal' }
    } finally { Move-Item $disabledCfg $cfg }
    $restored = (Invoke-Updater $updaterExe @('--installed-version', '--app-root', $productRoot) 'version-restored').Trim()
    if ($restored -ne $productVersion) { throw "Version detection did not recover: $restored" }
    Step 'Strict real-user E2E assertions passed'
} catch {
    $_ | Format-List * -Force | Out-String | Set-Content -LiteralPath $errorFile -Encoding utf8
    throw
} finally {
    if ($productInstalled -and $productMsi) { try { Invoke-Msi @('/x', "`"$productMsi`"", '/qn', '/norestart') 'Uninstall NicoCache_nl MSI' 'product-uninstall.log' } catch { $_ | Out-String | Add-Content $errorFile } }
    if ($updaterInstalled -and $updaterMsi) { try { Invoke-Msi @('/x', "`"$($updaterMsi.FullName)`"", '/qn', '/norestart') 'Uninstall updater MSI' 'updater-uninstall.log' } catch { $_ | Out-String | Add-Content $errorFile } }
    Stop-Transcript
}
Write-Output 'STRICT_REAL_USER_E2E_PASSED'
