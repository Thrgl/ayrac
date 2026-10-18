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

# Installer (Inno Setup, per-user) + update manifest. The installed app checks releases\latest.json for newer versions.
$iscc = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
if (Test-Path $iscc) {
  $setupSrc = Join-Path $work 'setup'
  Remove-Item -Recurse -Force $setupSrc -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force (Join-Path $setupSrc 'dict') | Out-Null
  Copy-Item (Join-Path $work 'dist\Ayrac.exe'), (Join-Path $src 'ayrac.ico') $setupSrc
  Copy-Item $dictFile (Join-Path $setupSrc 'dict')
  $manifest = Join-Path $releases 'latest.json'
  [IO.File]::WriteAllText((Join-Path $setupSrc 'update-source.txt'), ([Uri]$manifest).AbsoluteUri, (New-Object Text.UTF8Encoding $false))
  & $iscc /Q "/DAppVer=$version" "/DSrcDir=$setupSrc" "/DOutDir=$releases" (Join-Path $here 'installer.iss')
  if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }
  # Release notes for the update banner: the bullets of this version in CHANGELOG.md.
  $log = Get-Content -Raw -Encoding utf8 (Join-Path $root 'CHANGELOG.md')
  $m = [regex]::Match($log, "(?ms)^## $([regex]::Escape($version))\b.*?\r?\n(.*?)(?=^## |\z)")
  $notes = (($m.Groups[1].Value -split "`n") | Where-Object { $_ -match '^- ' } | ForEach-Object { ($_ -replace '^- ', '').Split(':')[0].Trim() } | Select-Object -First 3) -join " $([char]0xB7) "   # middle dot; keep this script ASCII (PowerShell 5 reads it as ANSI)
  $json = @{ version = $version; setup_url = "Ayrac-Setup-$version.exe"; notes = $notes } | ConvertTo-Json -Compress
  [IO.File]::WriteAllText($manifest, $json, (New-Object Text.UTF8Encoding $false))
} else { "Inno Setup not found ($iscc); skipping the installer" }

# Keep only the newest 4 versions in releases\ (~75 MB each); an older one can be rebuilt from its git tag.
$keep = 4
$vers = Get-ChildItem $releases -File | ForEach-Object { if ($_.Name -match '^Ayrac-(?:Setup-)?(\d+\.\d+\.\d+)\.exe$') { $matches[1] } } |
  Select-Object -Unique | Sort-Object { [version]$_ } -Descending
$old = @($vers | Select-Object -Skip $keep)
foreach ($v in $old) {
  Get-ChildItem $releases -File | Where-Object { $_.Name -eq "Ayrac-$v.exe" -or $_.Name -eq "Ayrac-Setup-$v.exe" } | Remove-Item -Force
}
if ($old.Count) { "releases: removed old versions " + ($old -join ', ') }
"Ayraç $version built"
Get-ChildItem $dist, $releases -Recurse -File | Select-Object FullName, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
