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
} finally {
    if ($database) {
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) |
            Out-Null
    }
    if ($installer) {
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) |
            Out-Null
    }
}
