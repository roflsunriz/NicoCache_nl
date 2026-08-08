#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,3}$')]
    [string]$AppVersion = '0.1.0',

    [ValidateSet('AppImage', 'Zip', 'Msi', 'All')]
    [string]$PackageType = 'AppImage',

    [string]$ZipFileName,

    [switch]$UseLegacyProgramsInstallPath
)

& (Join-Path $PSScriptRoot 'build-flat-package.ps1') @PSBoundParameters
