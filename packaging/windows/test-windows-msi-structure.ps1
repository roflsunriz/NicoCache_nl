[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$MsiPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$msi = (Resolve-Path -LiteralPath $MsiPath).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$summaryInformation = $installer.SummaryInformation($msi, 0)
if ([int]$summaryInformation.Property(1) -ne 932) {
    throw "MSIサマリーのコードページが日本語対応ではありません: $($summaryInformation.Property(1))"
}
$database = $installer.GetType().InvokeMember(
    'OpenDatabase', 'InvokeMethod', $null, $installer, @($msi, 0)
)

function Invoke-MsiQuery {
    param([string]$Sql, [int]$FieldCount)
    $view = $database.GetType().InvokeMember(
        'OpenView', 'InvokeMethod', $null, $database, @($Sql)
    )
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $rows = @()
    while ($true) {
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if ($null -eq $record) { break }
        $values = @()
        for ($index = 1; $index -le $FieldCount; $index++) {
            $values += $record.GetType().InvokeMember(
                'StringData', 'GetProperty', $null, $record, @($index)
            )
        }
        $rows += ,$values
    }
    return $rows
}

$fileRows = @(Invoke-MsiQuery 'SELECT `FileName`, `Directory_` FROM `File`, `Component` WHERE `File`.`Component_` = `Component`.`Component`' 2)
$fileNames = @($fileRows | ForEach-Object {
    $name = [string]$_[0]
    if ($name.Contains('|')) { $name.Split('|')[-1] } else { $name }
})
foreach ($required in @(
        'NicoCache_nl.jar', 'NicoCacheCA.jar', 'NicoCacheLauncher.jar',
        'NicoCacheBuild.jar', 'java.exe', 'javaw.exe', 'modules',
        'build-javac.ps1', 'NLMain.java'
    )) {
    if ($required -notin $fileNames) {
        throw "MSIにclone相当ルートまたは実行資材がありません: $required"
    }
}
foreach ($forbidden in @('NicoCache_nl.exe', 'NicoCache_nl.cfg')) {
    if ($forbidden -in $fileNames) { throw "MSIにjpackage固有ファイルが残っています: $forbidden" }
}
foreach ($jar in @('NicoCache_nl.jar', 'NicoCacheCA.jar', 'NicoCacheLauncher.jar',
        'NicoCacheBuild.jar')) {
    $row = @($fileRows | Where-Object {
        $name = [string]$_[0]
        ($name -eq $jar) -or $name.EndsWith("|$jar")
    })
    if ($row.Count -ne 1 -or [string]$row[0][1] -ne 'INSTALLFOLDER') {
        throw "JARがアプリケーションルート直下へ配置されません: $jar"
    }
}

$shortcutRows = @(Invoke-MsiQuery 'SELECT `Directory_`, `Target`, `Arguments`, `WkDir` FROM `Shortcut`' 4)
if ($shortcutRows.Count -ne 2 -or
        @($shortcutRows | ForEach-Object { [string]$_[0] } | Sort-Object) -join ',' -ne
        'ApplicationProgramsFolder,DesktopFolder') {
    throw "MSIのデスクトップ・スタートメニューショートカットが各1件ではありません: $($shortcutRows.Count)"
}
foreach ($shortcut in $shortcutRows) {
    if ([string]$shortcut[1] -ne '[INSTALLFOLDER]jre\bin\javaw.exe' -or
            [string]$shortcut[2] -notmatch '(?i)-jar' -or
            [string]$shortcut[2] -notmatch 'NicoCacheLauncher\.jar' -or
            [string]$shortcut[3] -ne 'INSTALLFOLDER') {
        throw "MSIショートカットが同梱JREとランチャーJARを参照していません: $($shortcut -join ' / ')"
    }
}
$registryRows = @(Invoke-MsiQuery 'SELECT `Registry`, `Key`, `Name`, `Value` FROM `Registry` WHERE `Key` = ''Software\NicoCache_nl\Installer''' 4)
if ($registryRows.Count -ne 1 -or [string]$registryRows[0][2] -ne 'InstallDir' -or
        [string]$registryRows[0][3] -ne '[INSTALLFOLDER]') {
    throw 'MSIにアンインストール用のインストール先登録がありません'
}
$customActions = @(Invoke-MsiQuery 'SELECT `Action`, `Type`, `Source`, `Target` FROM `CustomAction`' 4)
$restoreAction = @($customActions | Where-Object {
    [string]$_[0] -eq 'NicoCacheRestoreInstallDir'
})
if ($restoreAction.Count -ne 1 -or [int]$restoreAction[0][1] -ne 307 -or
        [string]$restoreAction[0][2] -ne 'INSTALLFOLDER' -or
        [string]$restoreAction[0][3] -ne '[NICOCACHE_INSTALLDIR]') {
    throw 'MSIの保存済みインストール先復元処理が不正です'
}
$rollbackAction = @($customActions | Where-Object {
    [string]$_[0] -eq 'NicoCacheRollbackWindowsSetup'
})
if ($rollbackAction.Count -ne 1 -or [int]$rollbackAction[0][1] -ne 34 -or
        [string]$rollbackAction[0][2] -ne 'TARGETDIR' -or
        [string]$rollbackAction[0][3] -notmatch 'first-run-setup\.ps1' -or
        [string]$rollbackAction[0][3] -notmatch '-Action Rollback' -or
        [string]$rollbackAction[0][3] -notmatch '-RemoveApplicationConfig' -or
        ([string]$rollbackAction[0][3]).Length -gt 255) {
    throw 'MSIにWindows設定を復元するアンインストール処理がありません'
}
$sequenceRows = @(Invoke-MsiQuery 'SELECT `Condition`, `Sequence` FROM `InstallExecuteSequence` WHERE `Action` = ''NicoCacheRollbackWindowsSetup''' 2)
$removeFilesRows = @(Invoke-MsiQuery 'SELECT `Sequence` FROM `InstallExecuteSequence` WHERE `Action` = ''RemoveFiles''' 1)
if ($sequenceRows.Count -ne 1 -or
        [string]$sequenceRows[0][0] -notmatch 'REMOVE="ALL"' -or
        [string]$sequenceRows[0][0] -notmatch 'NOT UPGRADINGPRODUCTCODE' -or
        $removeFilesRows.Count -ne 1 -or
        [int]$sequenceRows[0][1] -ge [int]$removeFilesRows[0][0]) {
    throw 'Windows設定の復元が通常アンインストールだけに限定されていません'
}
$tables = @(Invoke-MsiQuery 'SELECT `Name` FROM `_Tables`' 1 | ForEach-Object { [string]$_[0] })
if ($tables -contains 'WixRemoveFolderEx') {
    throw 'MSIにインストール先を再帰削除する定義が残っています'
}
Write-Output 'Windows MSI flat application-root structure tests passed'
