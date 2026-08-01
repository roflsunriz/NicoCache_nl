[CmdletBinding()]
param(
    [string]$LauncherJarPath,
    [string]$ApplicationRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:GITHUB_ACTIONS -ne 'true') {
    throw 'Windowsタスクスケジューラーの実適用試験は一時GitHub Actionsランナー以外では実行できません'
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($LauncherJarPath)) {
    $LauncherJarPath = Join-Path $root 'NicoCacheLauncher.jar'
}
if ([string]::IsNullOrWhiteSpace($ApplicationRoot)) {
    $ApplicationRoot = $root
}
$launcherJar = (Resolve-Path -LiteralPath $LauncherJarPath).Path
$applicationRootPath = (Resolve-Path -LiteralPath $ApplicationRoot).Path
$testRoot = Join-Path $root '.test-work\windows-task-scheduler'
$dataRoot = Join-Path $testRoot 'user-data'
$taskLabel = 'NicoCache-CI-' + [Guid]::NewGuid().ToString('N')
$taskLabelPattern = [regex]::Escape($taskLabel)
$nativeTask = $null

foreach ($requiredPath in @($launcherJar, $applicationRootPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "タスクスケジューラー試験の入力がありません: $requiredPath"
    }
}
New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null

function Get-MatchingNativeTasks {
    $output = @(& schtasks.exe /Query /FO LIST /V 2>&1)
    if ($LASTEXITCODE -ne 0) {
        return @()
    }
    $matches = @()
    foreach ($line in $output) {
        $match = [regex]::Match(
            [string]$line,
            '(?i)\\nicocache-nl-[^\s]*' + $taskLabelPattern)
        if ($match.Success) {
            $matches += $match.Value
        }
    }
    return @($matches | Sort-Object -Unique)
}

function Invoke-LauncherTaskCommand {
    param([string[]]$Arguments)

    & java -jar $launcherJar @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "起動管理アプリのタスク操作に失敗しました (ExitCode: $LASTEXITCODE)"
    }
}

function Get-TaskXml {
    param([Parameter(Mandatory)][string]$TaskPath)

    $output = @(& schtasks.exe /Query /TN $TaskPath /XML 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "登録したWindowsタスクを照会できません: $TaskPath"
    }
    try {
        return [xml]($output -join [Environment]::NewLine)
    } catch {
        throw "WindowsタスクのXMLを解析できません: $TaskPath"
    }
}

try {
    Invoke-LauncherTaskCommand @(
        '--headless',
        '--task-install',
        ('--app-root=' + $applicationRootPath),
        ('--data-root=' + $dataRoot),
        ('--task-name=' + $taskLabel)
    )

    $nativeTasks = @(Get-MatchingNativeTasks)
    if ($nativeTasks.Count -ne 1) {
        throw "登録したタスクを一意に特定できません: $($nativeTasks -join ', ')"
    }
    $nativeTask = $nativeTasks[0]
    $taskXml = Get-TaskXml $nativeTask
    $logonTrigger = $taskXml.Task.Triggers.LogonTrigger
    if ($null -eq $logonTrigger) {
        throw '登録タスクにLogonTriggerがありません'
    }
    if ([string]::IsNullOrWhiteSpace([string]$logonTrigger.UserId)) {
        throw '登録タスクにログオンユーザーがありません'
    }
    if ($null -ne $logonTrigger.Repetition) {
        throw '登録タスクに不要な繰り返し間隔があります'
    }
    $exec = $taskXml.Task.Actions.Exec
    if ($null -eq $exec) {
        throw '登録タスクに実行アクションがありません'
    }
    $command = [string]$exec.Command
    if ([string]::IsNullOrWhiteSpace($command) -or
            $command -notmatch '(?i)(java|NicoCache_nl)\.exe$') {
        throw "登録タスクの実行ファイルが不正です: $command"
    }
    if ($command -match '(?i)-jar') {
        throw "JVM引数が実行ファイル欄へ混入しています: $command"
    }
    $arguments = [string]$exec.Arguments
    if (($arguments -notmatch '--headless') -or
            ($arguments -notmatch '--start') -or
            ($arguments -notmatch [regex]::Escape($dataRoot))) {
        throw "登録タスクの起動引数が不正です: $arguments"
    }
    if ($arguments -match '/MO') {
        throw "登録タスクに不要な間隔指定が残っています: $arguments"
    }

    Invoke-LauncherTaskCommand @(
        '--headless',
        '--task-update',
        ('--app-root=' + $applicationRootPath),
        ('--data-root=' + $dataRoot),
        ('--task-name=' + $taskLabel)
    )
    $updatedXml = Get-TaskXml $nativeTask
    if ($null -eq $updatedXml.Task.Triggers.LogonTrigger) {
        throw '更新後のタスクからLogonTriggerが消えています'
    }

    Invoke-LauncherTaskCommand @(
        '--headless',
        '--task-remove',
        ('--app-root=' + $applicationRootPath),
        ('--data-root=' + $dataRoot),
        ('--task-name=' + $taskLabel)
    )
    $remaining = @(Get-MatchingNativeTasks)
    if ($remaining.Count -ne 0) {
        throw "削除後もWindowsタスクが残っています: $($remaining -join ', ')"
    }
    Write-Output 'Windows task scheduler integration tests passed'
} finally {
    foreach ($task in @(Get-MatchingNativeTasks)) {
        & schtasks.exe /Delete /TN $task /F 2>&1 | Out-Null
    }
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
        if ($resolvedTestRoot.StartsWith(
                $root + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
        }
    }
}
