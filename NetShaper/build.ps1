# Requires the .NET 8 SDK. Produces a self-contained build in .\dist
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".\WinDivert\WinDivert.dll")) { .\get-windivert.ps1 }
dotnet publish NetShaper.csproj -c Release -r win-x64 --self-contained true -o dist
Write-Host "Done. Run dist\NetShaper.exe (it will ask for Administrator)."
