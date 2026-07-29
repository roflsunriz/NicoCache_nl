[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$MsiPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
$installer = $null
$database = $null
$summaryInformation = $null

function Get-MsiRows {
    param(
        [Parameter(Mandatory)]$Database,
        [Parameter(Mandatory)][string]$Query,
        [Parameter(Mandatory)][int]$FieldCount
    )

    $view = $Database.GetType().InvokeMember(
        'OpenView',
        [Reflection.BindingFlags]::InvokeMethod,
        $null,
        $Database,
        @($Query)
    )
    try {
        $view.GetType().InvokeMember(
            'Execute',
            [Reflection.BindingFlags]::InvokeMethod,
            $null,
            $view,
            $null
        ) | Out-Null
        while ($record = $view.GetType().InvokeMember(
                'Fetch',
                [Reflection.BindingFlags]::InvokeMethod,
                $null,
                $view,
                $null
            )) {
            try {
                $values = @()
                for ($index = 1; $index -le $FieldCount; $index++) {
                    $values += $record.StringData($index)
                }
                [PSCustomObject]@{ Values = [string[]]$values }
            } finally {
                [Runtime.InteropServices.Marshal]::FinalReleaseComObject(
                    $record
                ) | Out-Null
            }
        }
    } finally {
        $view.GetType().InvokeMember(
            'Close',
            [Reflection.BindingFlags]::InvokeMethod,
            $null,
            $view,
            $null
        ) | Out-Null
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) |
            Out-Null
    }
}

function Test-DirectoryRoot {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][string]$ExpectedRoot,
        [Parameter(Mandatory)][hashtable]$Parents
    )

    $current = $Directory
    $visited = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase
    )
    while ($current) {
        if ($current -eq $ExpectedRoot) {
            return $true
        }
        if (-not $visited.Add($current) -or
                -not $Parents.ContainsKey($current)) {
            return $false
        }
        $current = $Parents[$current]
    }
    return $false
}

try {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.GetType().InvokeMember(
        'OpenDatabase',
        [Reflection.BindingFlags]::InvokeMethod,
        $null,
        $installer,
        @($resolvedMsi, 0)
    )
    $summaryInformation = $installer.SummaryInformation($resolvedMsi, 0)
    $summaryCodepage = $summaryInformation.Property(1)
    if ([int]$summaryCodepage -ne 932) {
        throw "MSIサマリーのコードページが日本語対応ではありません: $summaryCodepage"
    }
    Write-Output 'PASS MSIサマリーのコードページ932'

    $parents = @{}
    foreach ($row in @(Get-MsiRows `
            -Database $database `
            -Query 'SELECT `Directory`, `Directory_Parent` FROM `Directory`' `
            -FieldCount 2)) {
        $parents[$row.Values[0]] = $row.Values[1]
    }
    $shortcuts = @(Get-MsiRows `
        -Database $database `
        -Query 'SELECT `Directory_`, `Name`, `Target`, `Component_` FROM `Shortcut`' `
        -FieldCount 4)
    $fileNames = @{}
    foreach ($row in @(Get-MsiRows `
            -Database $database `
            -Query 'SELECT `File`, `FileName` FROM `File`' `
            -FieldCount 2)) {
        $fileNames[$row.Values[0]] = $row.Values[1]
    }
    $componentConditions = @{}
    foreach ($row in @(Get-MsiRows `
            -Database $database `
            -Query 'SELECT `Component`, `Condition` FROM `Component`' `
            -FieldCount 2)) {
        $componentConditions[$row.Values[0]] = $row.Values[1]
    }
    $properties = @{}
    foreach ($row in @(Get-MsiRows `
            -Database $database `
            -Query 'SELECT `Property`, `Value` FROM `Property`' `
            -FieldCount 2)) {
        $properties[$row.Values[0]] = $row.Values[1]
    }
    $customActions = @(Get-MsiRows `
        -Database $database `
        -Query 'SELECT `Action`, `Type`, `Source`, `Target` FROM `CustomAction`' `
        -FieldCount 4)
    $executeSequence = @(Get-MsiRows `
        -Database $database `
        -Query 'SELECT `Action`, `Condition`, `Sequence` FROM `InstallExecuteSequence`' `
        -FieldCount 3)
    $tableNames = @(
        Get-MsiRows `
            -Database $database `
            -Query 'SELECT `Name` FROM `_Tables`' `
            -FieldCount 1 |
            ForEach-Object { $_.Values[0] }
    )
    if ($tableNames -contains 'WixRemoveFolderEx') {
        throw 'MSIに導入先を再帰削除するWixRemoveFolderExが残っています'
    }
    Write-Output 'PASS 更新時にユーザーデータを再帰削除しない定義'

    $desktopShortcuts = @(
        $shortcuts |
            Where-Object {
                Test-DirectoryRoot `
                    -Directory $_.Values[0] `
                    -ExpectedRoot 'DesktopFolder' `
                    -Parents $parents
            }
    )
    if ($desktopShortcuts.Count -ne 1) {
        throw "デスクトップショートカットが1件ではありません: $($desktopShortcuts.Count)"
    }
    $startMenuShortcuts = @(
        $shortcuts |
            Where-Object {
                Test-DirectoryRoot `
                    -Directory $_.Values[0] `
                    -ExpectedRoot 'ProgramMenuFolder' `
                    -Parents $parents
            }
    )
    if ($startMenuShortcuts.Count -ne 1) {
        throw "スタートメニューショートカットが1件ではありません: $($startMenuShortcuts.Count)"
    }
    $expectedShortcuts = @(
        [PSCustomObject]@{
            Shortcut = $desktopShortcuts[0]
            Condition = 'JP_INSTALL_DESKTOP_SHORTCUT'
        }
        [PSCustomObject]@{
            Shortcut = $startMenuShortcuts[0]
            Condition = 'JP_INSTALL_STARTMENU_SHORTCUT'
        }
    )
    foreach ($expected in $expectedShortcuts) {
        $shortcut = $expected.Shortcut
        if ($shortcut.Values[1] -notmatch 'NicoCache_nl') {
            throw "ショートカット名が不正です: $($shortcut.Values -join ', ')"
        }
        if ($shortcut.Values[2] -notmatch '^\[#([^\]]+)\]$') {
            throw "ショートカット対象がファイル参照ではありません: $($shortcut.Values[2])"
        }
        $targetFile = $Matches[1]
        if (-not $fileNames.ContainsKey($targetFile) -or
                $fileNames[$targetFile] -notmatch 'NicoCache_nl(?:\.exe)?$') {
            throw "ショートカットが製品ランチャーを指していません: $($shortcut.Values[2])"
        }
        $component = $shortcut.Values[3]
        if (-not $componentConditions.ContainsKey($component) -or
                $componentConditions[$component] -ne $expected.Condition) {
            throw "ショートカットの作成条件が不正です: $component"
        }
        if (-not $properties.ContainsKey($expected.Condition) -or
                $properties[$expected.Condition] -ne '1') {
            throw "ショートカットが既定で有効ではありません: $($expected.Condition)"
        }
    }
    Write-Output 'PASS MSI内のデスクトップ・スタートメニューショートカット定義'

    $restoreInstallDirActions = @(
        $customActions |
            Where-Object { $_.Values[0] -eq 'NicoCacheRestoreInstallDir' }
    )
    if ($restoreInstallDirActions.Count -ne 1) {
        throw "導入先復元アクションが1件ではありません: $($restoreInstallDirActions.Count)"
    }
    $restoreInstallDirAction = $restoreInstallDirActions[0]
    if ([int]$restoreInstallDirAction.Values[1] -ne 307 -or
            $restoreInstallDirAction.Values[2] -ne 'INSTALLDIR' -or
            $restoreInstallDirAction.Values[3] -ne '[NICOCACHE_INSTALLDIR]') {
        throw "導入先復元アクションが不正です: $($restoreInstallDirAction.Values -join ', ')"
    }
    $restoreInstallDirSequenceRows = @(
        $executeSequence |
            Where-Object { $_.Values[0] -eq 'NicoCacheRestoreInstallDir' }
    )
    if ($restoreInstallDirSequenceRows.Count -ne 1 -or
            $restoreInstallDirSequenceRows[0].Values[1] -ne
            'NICOCACHE_INSTALLDIR') {
        throw '導入先復元の実行条件が不正です'
    }
    $costInitializeRows = @(
        $executeSequence |
            Where-Object { $_.Values[0] -eq 'CostInitialize' }
    )
    if ($costInitializeRows.Count -ne 1 -or
            [int]$restoreInstallDirSequenceRows[0].Values[2] -ge
            [int]$costInitializeRows[0].Values[2]) {
        throw '保存済み導入先がCostInitializeより前に復元されません'
    }
    Write-Output 'PASS MSI内の保存済み導入先復元定義'

    $rollbackActions = @(
        $customActions |
            Where-Object { $_.Values[0] -eq 'NicoCacheRollbackWindowsSetup' }
    )
    if ($rollbackActions.Count -ne 1) {
        throw "Windows設定復元アクションが1件ではありません: $($rollbackActions.Count)"
    }
    $rollbackAction = $rollbackActions[0]
    if ([int]$rollbackAction.Values[1] -ne 34) {
        throw "Windows設定復元アクションが同期・失敗検出型ではありません: $($rollbackAction.Values[1])"
    }
    if ($rollbackAction.Values[2] -ne 'TARGETDIR') {
        throw "Windows設定復元アクションの実行場所が不正です: $($rollbackAction.Values[2])"
    }
    foreach ($requiredArgument in @(
            '[NICOCACHE_INSTALLDIR]',
            'setup\windows\first-run-setup.ps1',
            '-Action Rollback',
            '-RemoveApplicationConfig'
        )) {
        if ($rollbackAction.Values[3] -notmatch
                [regex]::Escape($requiredArgument)) {
            throw "Windows設定復元アクションに必要な引数がありません: $requiredArgument"
        }
    }
    if ($rollbackAction.Values[3].Length -gt 255) {
        throw "Windows設定復元アクションがMSIの255文字上限を超えています: $($rollbackAction.Values[3].Length)"
    }

    $rollbackSequenceRows = @(
        $executeSequence |
            Where-Object { $_.Values[0] -eq 'NicoCacheRollbackWindowsSetup' }
    )
    if ($rollbackSequenceRows.Count -ne 1) {
        throw "Windows設定復元の実行順序が1件ではありません: $($rollbackSequenceRows.Count)"
    }
    $rollbackSequence = $rollbackSequenceRows[0]
    if (($rollbackSequence.Values[1] -replace '\s+', ' ').Trim() -ne
            'REMOVE="ALL" AND NOT UPGRADINGPRODUCTCODE AND NICOCACHE_INSTALLDIR') {
        throw "Windows設定復元の実行条件が不正です: $($rollbackSequence.Values[1])"
    }
    $removeFilesRows = @(
        $executeSequence |
            Where-Object { $_.Values[0] -eq 'RemoveFiles' }
    )
    if ($removeFilesRows.Count -ne 1 -or
            [int]$rollbackSequence.Values[2] -ge
            [int]$removeFilesRows[0].Values[2]) {
        throw 'Windows設定復元が製品ファイル削除より前に実行されません'
    }
    Write-Output 'PASS MSI内のアンインストール前Windows設定復元定義'
} finally {
    if ($summaryInformation) {
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject(
            $summaryInformation
        ) | Out-Null
    }
    if ($database) {
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) |
            Out-Null
    }
    if ($installer) {
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) |
            Out-Null
    }
}
