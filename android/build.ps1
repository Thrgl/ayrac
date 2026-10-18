# Builds android\releases\Ayrac-<version>.apk from ..\ayrac.html (shared with the desktop app).
# Usage:  powershell -ExecutionPolicy Bypass -File android\build.ps1
# Needs: JDK 17 in %USERPROFILE%\Tools, Gradle 8.11.1 in %USERPROFILE%\Tools, Android SDK in %USERPROFILE%\Android\Sdk,
#        the dictionary %TEMP%\ayrac-build\dict\en-tr.sqlite (desktop\build_dict.py) and the build venv (desktop\build.ps1).
# Keep this script ASCII: Windows PowerShell 5 reads it with the ANSI code page.
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$env:JAVA_HOME = Get-ChildItem (Join-Path $env:USERPROFILE 'Tools') -Directory -Filter 'jdk-17*' | Select-Object -First 1 -ExpandProperty FullName
$env:ANDROID_HOME = Join-Path $env:USERPROFILE 'Android\Sdk'
$gradle = Join-Path $env:USERPROFILE 'Tools\gradle-8.11.1\bin\gradle.bat'
$py = Join-Path $env:TEMP 'ayrac-build\venv\Scripts\python.exe'
$version = (Get-Content (Join-Path $root 'desktop\VERSION') -Raw).Trim()
$utf8 = New-Object Text.UTF8Encoding $false

# local.properties: where the SDK is
[IO.File]::WriteAllText((Join-Path $here 'local.properties'), 'sdk.dir=' + $env:ANDROID_HOME.Replace('\', '\\'), $utf8)

# Assets: the reader page with the Android bridge, and the dictionary
$assets = Join-Path $here 'app\src\main\assets'
New-Item -ItemType Directory -Force (Join-Path $assets 'web'), (Join-Path $assets 'dict') | Out-Null
$page = Get-Content -Raw -Encoding utf8 (Join-Path $root 'ayrac.html')
$shell = '<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' +
  '<script>window.AYRAC_DESKTOP=true;window.AYRAC_PLATFORM="android";window.AYRAC_VERSION="' + $version + '";</script>' +
  '<script src="android-bridge.js"></script></head><body>' + $page + '</body></html>'
[IO.File]::WriteAllText((Join-Path $assets 'web\index.html'), $shell, $utf8)
Copy-Item (Join-Path $here 'web\android-bridge.js') (Join-Path $assets 'web') -Force
Copy-Item (Join-Path $env:TEMP 'ayrac-build\dict\en-tr.sqlite') (Join-Path $assets 'dict') -Force

# Launcher icons
& $py (Join-Path $here 'make_icons.py') (Join-Path $here 'app\src\main\res')

# Signing key (made once; android\keystore.jks + keystore.properties stay out of git - back them up!)
$ks = Join-Path $here 'keystore.jks'
if (-not (Test-Path $ks)) {
  $pass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
  & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $ks -alias ayrac -keyalg RSA -keysize 2048 -validity 36500 `
      -storepass $pass -keypass $pass -dname 'CN=Ayrac, O=Tahir, C=TR' | Out-Null
  [IO.File]::WriteAllText((Join-Path $here 'keystore.properties'), "storeFile=keystore.jks`nstorePassword=$pass`nkeyAlias=ayrac`nkeyPassword=$pass`n", $utf8)
}

Push-Location $here
try {
  & $gradle --no-daemon -q assembleRelease
  if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
} finally { Pop-Location }

$releases = Join-Path $here 'releases'
New-Item -ItemType Directory -Force $releases | Out-Null
$apk = Join-Path $releases "Ayrac-$version.apk"
Copy-Item (Join-Path $here 'app\build\outputs\apk\release\app-release.apk') $apk -Force

# Keep only the newest 4 APKs; an older one can be rebuilt from its git tag.
$old = @(Get-ChildItem $releases -File | Where-Object { $_.Name -match '^Ayrac-(\d+\.\d+\.\d+)\.apk$' } |
  Sort-Object { [version]($_.Name -replace '^Ayrac-|\.apk$', '') } -Descending | Select-Object -Skip 4)
$old | Remove-Item -Force
if ($old.Count) { "releases: removed " + (($old | ForEach-Object { $_.Name }) -join ', ') }
Get-Item $apk | Select-Object FullName, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
