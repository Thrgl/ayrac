# Builds desktop\dist\Ayrac.exe from ..\ayrac.html and ayrac_app.py.
# Usage:  powershell -ExecutionPolicy Bypass -File desktop\build.ps1 [-Run]
# The build runs in %TEMP%\ayrac-build (short path; Microsoft Store Python cannot write into AppData\Roaming).
param([switch]$Run)
$ErrorActionPreference = 'Stop'

$here  = Split-Path -Parent $MyInvocation.MyCommand.Path
$root  = Split-Path -Parent $here
$work  = Join-Path $env:TEMP 'ayrac-build'
$venv  = Join-Path $work 'venv'
$src   = Join-Path $work 'src'
$py    = Join-Path $venv 'Scripts\python.exe'

if (-not (Test-Path $py)) {
  python -m venv $venv
  & $py -m pip install --disable-pip-version-check -q -r (Join-Path $here 'requirements.txt')
}

# Stage sources: the reader page gets a doctype shell and the desktop flag.
Remove-Item -Recurse -Force $src -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $src 'web') | Out-Null
Copy-Item (Join-Path $here 'ayrac_app.py'), (Join-Path $here 'make_icon.py'), (Join-Path $here 'VERSION') $src
$version = (Get-Content (Join-Path $here 'VERSION') -Raw).Trim()
$page = Get-Content -Raw -Encoding utf8 (Join-Path $root 'ayrac.html')
$shell = '<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><script>window.AYRAC_DESKTOP=true;window.AYRAC_VERSION="' + $version + '";</script></head><body>' + $page + '</body></html>'
[IO.File]::WriteAllText((Join-Path $src 'web\index.html'), $shell, (New-Object Text.UTF8Encoding $false))

# Offline dictionary (built by build_dict.py into %TEMP%\ayrac-build\dict). It ships next to Ayrac.exe, not inside it.
$dictFile = Join-Path $work 'dict\en-tr.sqlite'
if (-not (Test-Path $dictFile)) {
  New-Item -ItemType Directory -Force (Split-Path $dictFile) | Out-Null
  & $py -m pip install --disable-pip-version-check -q wordfreq
  & $py -u (Join-Path $here 'build_dict.py') $dictFile
}
New-Item -ItemType Directory -Force (Join-Path $src 'dict') | Out-Null
Copy-Item $dictFile (Join-Path $src 'dict') -Force

Push-Location $src
try {
  & $py make_icon.py ayrac.ico
  if ($Run) { & $py ayrac_app.py; return }
  & $py -m PyInstaller --noconfirm --clean --onefile --windowed --name Ayrac `
      --icon (Join-Path $src 'ayrac.ico') --add-data "$src\web;web" --add-data "$src\ayrac.ico;." --add-data "$src\VERSION;." `
      --hidden-import keyring.backends.Windows `
      --distpath (Join-Path $work 'dist') --workpath (Join-Path $work 'build') --specpath $work `
      ayrac_app.py
  if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed ($LASTEXITCODE)" }
} finally { Pop-Location }

$dist = Join-Path $here 'dist'
New-Item -ItemType Directory -Force (Join-Path $dist 'dict') | Out-Null
Copy-Item (Join-Path $work 'dist\Ayrac.exe') $dist -Force
Copy-Item $dictFile (Join-Path $dist 'dict') -Force
# Keep every version so we can go back.
$releases = Join-Path $here 'releases'
New-Item -ItemType Directory -Force $releases | Out-Null
Copy-Item (Join-Path $work 'dist\Ayrac.exe') (Join-Path $releases "Ayrac-$version.exe") -Force
"Ayraç $version built"
Get-ChildItem $dist, $releases -Recurse -File | Select-Object FullName, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
