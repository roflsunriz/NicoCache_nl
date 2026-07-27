[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ApplicationRoot,
    [int]$WaitForProcessId = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$stateRoot = Join-Path $ApplicationRoot '.runtime-dependency-updater'
$pendingPath = Join-Path $stateRoot 'pending-update.json'
$backupRoot = Join-Path $stateRoot 'backups'
if (-not (Test-Path -LiteralPath $pendingPath -PathType Leaf)) { return }

if ($WaitForProcessId -gt 0) {
    try { Wait-Process -Id $WaitForProcessId -Timeout 120 -ErrorAction Stop }
    catch [Microsoft.PowerShell.Commands.ProcessCommandException] { }
}

$pending = Get-Content -LiteralPath $pendingPath -Raw | ConvertFrom-Json
$root = [IO.Path]::GetFullPath($ApplicationRoot).TrimEnd('\', '/')
$source = [IO.Path]::GetFullPath([string]$pending.Source)
$destination = [IO.Path]::GetFullPath([string]$pending.Destination)
if (-not $destination.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "管理対象外の置換先です: $destination"
}
if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "ステージ済みランタイムがありません: $source"
}

New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
$backup = Join-Path $backupRoot ("{0}-{1:yyyyMMddHHmmssfff}" -f $pending.Id, (Get-Date))
if (Test-Path -LiteralPath $destination) {
    Move-Item -LiteralPath $destination -Destination $backup
}
try {
    Move-Item -LiteralPath $source -Destination $destination
    Remove-Item -LiteralPath $pendingPath -Force
} catch {
    if (Test-Path -LiteralPath $destination) {
        Remove-Item -LiteralPath $destination -Recurse -Force
    }
    if (Test-Path -LiteralPath $backup) {
        Move-Item -LiteralPath $backup -Destination $destination
    }
    throw
}
