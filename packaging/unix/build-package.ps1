#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^\d+(?:\.\d+){0,2}$')]
    [string]$AppVersion = '0.1.0',

    [ValidateSet('Linux', 'MacOS')]
    [string]$Platform,

    [ValidateSet('AppImage', 'Zip', 'Deb', 'Rpm', 'Pkg', 'Dmg', 'All')]
    [string]$PackageType = 'AppImage'
)

& (Join-Path $PSScriptRoot 'build-flat-package.ps1') @PSBoundParameters
