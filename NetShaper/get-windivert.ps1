# Downloads WinDivert 2.2.2 (signed driver + DLL) into .\WinDivert
$ErrorActionPreference = "Stop"
$ver = "2.2.2"
$url = "https://github.com/basil00/WinDivert/releases/download/v$ver/WinDivert-$ver-A.zip"
$zip = Join-Path $env:TEMP "windivert-$ver.zip"
$tmp = Join-Path $env:TEMP "windivert-$ver"
Invoke-WebRequest $url -OutFile $zip
Expand-Archive $zip -DestinationPath $tmp -Force
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\WinDivert" | Out-Null
Copy-Item "$tmp\WinDivert-$ver-A\x64\WinDivert.dll"   "$PSScriptRoot\WinDivert\" -Force
Copy-Item "$tmp\WinDivert-$ver-A\x64\WinDivert64.sys" "$PSScriptRoot\WinDivert\" -Force
Write-Host "WinDivert files copied to $PSScriptRoot\WinDivert"
