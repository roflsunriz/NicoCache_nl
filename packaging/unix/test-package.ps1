#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Linux', 'MacOS')]
    [string]$Platform,

    [ValidatePattern('^\d+(?:\.\d+){0,2}$')]
    [string]$AppVersion = '0.1.0'
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
$workRoot = Join-Path $root (Join-Path '.test-work' ('unix-package-' + $Platform.ToLowerInvariant()))
$outputRoot = Join-Path $workRoot 'output'
$bundleName = 'NicoCache_nl' + $(if ($Platform -eq 'MacOS') { '.app' } else { '' })
$bundle = Join-Path $outputRoot $bundleName
$contentRoot = if ($Platform -eq 'MacOS') { Join-Path $bundle 'Contents' } else { $bundle }
$resourceDirectory = if ($Platform -eq 'MacOS') {
    Join-Path $contentRoot 'Resources'
} else {
    $contentRoot
}
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

Assert-True (Test-Path -LiteralPath $bundle -PathType Container) "アプリイメージがありません: $bundle"
if ($Platform -eq 'Linux') {
    $launcher = Join-Path $bundle 'bin/NicoCache_nl'
    Assert-File $launcher
    Assert-True ((Get-Item -LiteralPath $launcher).UnixFileMode.ToString().Contains('UserExecute')) `
        'Linuxランチャーに実行属性がありません'
} else {
    $launcher = Join-Path $contentRoot 'MacOS/NicoCache_nl'
    Assert-File $launcher
}
Assert-File (Join-Path $applicationDirectory 'NicoCache_nl.jar')
Assert-File (Join-Path $applicationDirectory 'NicoCacheCA.jar')
Assert-File (Join-Path $applicationDirectory 'NicoCacheLauncher.jar')
Assert-File (Join-Path $applicationDirectory 'NicoCacheBuild.jar')
$launcherJarPath = Join-Path $applicationDirectory 'NicoCacheLauncher.jar'
$launcherArchive = [System.IO.Compression.ZipFile]::OpenRead($launcherJarPath)
try {
    $launcherEntries = @($launcherArchive.Entries |
        Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'nicocache/launcher/LauncherMain.class',
            'nicocache/launcher/messages.properties',
            'nicocache/launcher/messages_ja.properties'
        )) {
        Assert-True ($requiredEntry -in $launcherEntries) `
            "ランチャーJARに必要な要素がありません: $requiredEntry"
    }
} finally {
    $launcherArchive.Dispose()
}
$buildJarPath = Join-Path $applicationDirectory 'NicoCacheBuild.jar'
$buildArchive = [System.IO.Compression.ZipFile]::OpenRead($buildJarPath)
try {
    $buildEntries = @($buildArchive.Entries | Select-Object -ExpandProperty FullName)
    Assert-True ($null -ne $buildEntries -and
        'nicocache/build/BuildMain.class' -in $buildEntries) `
        'ビルド管理JARに必要なエントリがありません: nicocache/build/BuildMain.class'
} finally {
    $buildArchive.Dispose()
}
Assert-File (Join-Path $applicationDirectory 'lib/bcpkix.jar')
Assert-File (Join-Path $applicationDirectory 'lib/bcprov.jar')
Assert-File (Join-Path $applicationDirectory 'lib/bcutil.jar')
Assert-File (Join-Path $applicationDirectory 'NicoCache_nl.cfg')
Assert-File (Join-Path $resourceDirectory 'tools/cmaf-to-mp4/nico-cmaf-to-mp4.jar')
Assert-File (Join-Path $resourceDirectory 'tools/cmaf-to-mp4/README.md')
$cmafJarPath = Join-Path $resourceDirectory 'tools/cmaf-to-mp4/nico-cmaf-to-mp4.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$cmafArchive = [System.IO.Compression.ZipFile]::OpenRead($cmafJarPath)
try {
    $cmafEntries = @($cmafArchive.Entries | Select-Object -ExpandProperty FullName)
    foreach ($requiredEntry in @(
            'META-INF/MANIFEST.MF',
            'nicocache/cmaftomp4/Main.class'
        )) {
        Assert-True ($requiredEntry -in $cmafEntries) `
            "同梱CMAF/Domand変換アプリJARに必要な要素がありません: $requiredEntry"
    }
} finally {
    $cmafArchive.Dispose()
}
Assert-File (Join-Path $resourceDirectory 'NicoCache_nl.version')
Assert-True ((Get-Content -Raw -LiteralPath (Join-Path $resourceDirectory 'NicoCache_nl.version')).Trim() -eq $AppVersion) `
    '本体の公開版番号メタデータが不正です'
Assert-File (Join-Path $runtimeDirectory 'lib/modules')
Assert-File (Join-Path $runtimeDirectory 'bin/java')
Assert-File (Join-Path $resourceDirectory 'config.properties.default')
Assert-File (Join-Path $resourceDirectory 'how-to-update.md')
Assert-File (Join-Path $resourceDirectory 'documents/api.md')
Assert-File (Join-Path $resourceDirectory 'documents/tls.md')
Assert-File (Join-Path $resourceDirectory 'documents/user-data-root.md')
Assert-File (Join-Path $resourceDirectory 'packaging/windows/README.md')
Assert-File (Join-Path $resourceDirectory 'packaging/unix/README.md')
Assert-File (Join-Path $resourceDirectory 'tests/README.md')
Assert-File (Join-Path $resourceDirectory 'nlFilters/how-to-update.md')
Assert-File (Join-Path $resourceDirectory 'nlFilters/tools/nlfilter-lab/README.md')
Assert-File (Join-Path $resourceDirectory 'local/nllib.js')
Assert-File (Join-Path $resourceDirectory 'nlFilters/01_globalFilter.txt')
Assert-File (Join-Path $resourceDirectory 'data/tlsclient/cacerts2')
Assert-True (-not @(Get-ChildItem -LiteralPath $bundle -Recurse -File -Filter '*.ps1').Count) `
    'PowerShellスクリプトがUnixパッケージへ混入しています'
Assert-True (-not @(Get-ChildItem -LiteralPath $bundle -Recurse -File -Filter '*.dll').Count) `
    'Windows DLLがUnixパッケージへ混入しています'

$helpOutput = @(& $launcher '--help' 2>&1)
Assert-True ($LASTEXITCODE -eq 0) "Unixランチャーの--helpに失敗しました: $helpOutput"
Assert-True (($helpOutput -join "`n").Contains('--tray')) `
    'Unixランチャーにタスクトレイ起動オプションがありません'
Assert-True (($helpOutput -join "`n").Contains('--minimized')) `
    'Unixランチャーに最小化起動オプションがありません'

$sandbox = Join-Path $workRoot 'launcher-sandbox'
if (Test-Path -LiteralPath $sandbox) { Remove-Item -LiteralPath $sandbox -Recurse -Force }
New-Item -ItemType Directory -Path $sandbox | Out-Null
$dataRoot = Join-Path $sandbox 'data'
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $launcher
$startInfo.WorkingDirectory = $sandbox
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
foreach ($argument in @(
        '--setup', '--headless', "--user-data-root=$dataRoot", '--https=true',
        '--trust-certificate=false', '--proxy=false', '--autostart=false')) {
    [void]$startInfo.ArgumentList.Add($argument)
}
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
[void]$process.Start()
if (-not $process.WaitForExit(120000)) {
    $process.Kill($true)
    throw 'Unixアプリイメージの初回セットアップ起動がタイムアウトしました'
}
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
Assert-True ($process.ExitCode -eq 0) "初回セットアップ起動に失敗しました: $stdout$stderr"
Assert-File (Join-Path $resourceDirectory 'config.properties')
Assert-File (Join-Path $dataRoot 'data/first-run-setup.properties')
Assert-File (Join-Path $dataRoot 'certs/ca.cer')
Assert-File (Join-Path $dataRoot 'certs/site.jks')
# OSの自動プロキシーは変更せず、診断に必要な利用者資材だけを隔離領域へ配置する。
$proxyPacPath = Join-Path $dataRoot 'proxy.pac'
Copy-Item -LiteralPath (Join-Path $resourceDirectory 'proxy_sample.pac') `
    -Destination $proxyPacPath
Assert-File $proxyPacPath
$checkOutput = & $launcher '--headless' '--check-data-root' 2>&1
Assert-True ($LASTEXITCODE -eq 0) `
    "ユーザーデータルート診断が完全になりません: $checkOutput"

$archive = Join-Path $outputRoot "NicoCache_nl-$AppVersion-$($Platform.ToLowerInvariant())-$architecture.zip"
if (Test-Path -LiteralPath $archive -PathType Leaf) {
    $unzip = Get-RequiredCommand 'unzip'
    & $unzip -t $archive | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) "本体ZIPの検証に失敗しました: $archive"
    $zipEntries = @(& $unzip -Z1 $archive)
    Assert-True ($LASTEXITCODE -eq 0) "本体ZIPの一覧取得に失敗しました: $archive"
    $zipApplicationRoot = if ($Platform -eq 'MacOS') {
        "$bundleName/Contents/app"
    } else {
        "$bundleName/lib/app"
    }
    foreach ($artifactName in @(
            'NicoCache_nl.jar', 'NicoCacheCA.jar', 'NicoCacheLauncher.jar',
            'NicoCacheBuild.jar')) {
        Assert-True ("$zipApplicationRoot/$artifactName" -in $zipEntries) `
            "本体ZIPに独立アプリJARがありません: $artifactName"
    }
    $zipToolRoot = if ($Platform -eq 'MacOS') {
        "$bundleName/Contents/Resources/tools/cmaf-to-mp4"
    } else {
        "$bundleName/tools/cmaf-to-mp4"
    }
    foreach ($relativePath in @('nico-cmaf-to-mp4.jar', 'README.md')) {
        Assert-True ("$zipToolRoot/$relativePath" -in $zipEntries) `
            "本体ZIPにCMAF/Domand変換アプリがありません: $relativePath"
    }
    $userDataGuideEntry = if ($Platform -eq 'MacOS') {
        "$bundleName/Contents/Resources/documents/user-data-root.md"
    } else {
        "$bundleName/documents/user-data-root.md"
    }
    Assert-True ($userDataGuideEntry -in $zipEntries) `
        '本体ZIPにユーザーデータルートの説明書がありません'
}
if ($Platform -eq 'Linux') {
    foreach ($extension in @('.deb', '.rpm')) {
        $package = Get-ChildItem -LiteralPath $outputRoot -Filter "*linux-$architecture$extension" -File |
            Select-Object -First 1
        Assert-True ($null -ne $package) "Linux $extension がありません"
        if ($extension -eq '.deb') {
            & (Get-RequiredCommand 'dpkg-deb') --info $package.FullName | Out-Null
            $packageEntries = @(& (Get-RequiredCommand 'dpkg-deb') --contents $package.FullName)
        } else {
            $packageEntries = @(& (Get-RequiredCommand 'rpm') -qpl $package.FullName)
        }
        Assert-True ($LASTEXITCODE -eq 0) "Linux $extension のメタデータ検証に失敗しました"
        foreach ($artifactName in @(
                'NicoCache_nl.jar', 'NicoCacheCA.jar', 'NicoCacheLauncher.jar',
                'NicoCacheBuild.jar')) {
            Assert-True (@($packageEntries | Where-Object {
                    $_ -match [regex]::Escape("/lib/app/$artifactName")
                }).Count -gt 0) `
                "Linux $extension に独立アプリJARがありません: $artifactName"
        }
        Assert-True (@($packageEntries | Where-Object { $_ -match '/lib/tools/cmaf-to-mp4/nico-cmaf-to-mp4\.jar' }).Count -gt 0) `
            "Linux $extension にCMAF/Domand変換アプリがありません"
    }
} else {
    $pkg = Get-ChildItem -LiteralPath $outputRoot -Filter "*macos-$architecture.pkg" -File |
        Select-Object -First 1
    Assert-True ($null -ne $pkg) 'macOS PKGがありません'
    $pkgEntries = @(& (Get-RequiredCommand 'pkgutil') --payload-files $pkg.FullName)
    Assert-True ($LASTEXITCODE -eq 0) 'macOS PKGのペイロード一覧を取得できません'
    foreach ($artifactName in @(
            'NicoCache_nl.jar', 'NicoCacheCA.jar', 'NicoCacheLauncher.jar',
            'NicoCacheBuild.jar')) {
        Assert-True (@($pkgEntries | Where-Object {
                $_ -match [regex]::Escape("Contents/app/$artifactName")
            }).Count -gt 0) `
            "macOS PKGに独立アプリJARがありません: $artifactName"
    }
    Assert-True (@($pkgEntries | Where-Object { $_ -match 'Contents/Resources/tools/cmaf-to-mp4/nico-cmaf-to-mp4\.jar' }).Count -gt 0) `
        'macOS PKGにCMAF/Domand変換アプリがありません'
    foreach ($extension in @('.pkg', '.dmg')) {
        $package = Get-ChildItem -LiteralPath $outputRoot -Filter "*macos-$architecture$extension" -File |
            Select-Object -First 1
        Assert-True ($null -ne $package) "macOS $extension がありません"
        Assert-True ($package.Length -gt 0) "macOS $extension が空です"
    }
}

Write-Output "${Platform}本体パッケージの構造・隔離起動テストに成功しました"
