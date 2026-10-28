# Clones the upstream exploit and drops the prebuilt binary into assets/.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cve98260-" + [guid]::NewGuid().ToString('N'))

git clone --depth 1 https://github.com/YuKongA/CVE-2026-98260.git $tmp
Copy-Item (Join-Path $tmp 'timer/timer') (Join-Path $root 'app/src/main/assets/timer') -Force
Remove-Item $tmp -Recurse -Force
Write-Host "assets/timer: $((Get-Item (Join-Path $root 'app/src/main/assets/timer')).Length) bytes"
