# Runs the Android unit tests: the dictionary cases shared with the desktop tests (desktop\tests\dict_cases.json).
# Usage:  powershell -ExecutionPolicy Bypass -File android\test.ps1
# Needs the same tools as build.ps1 and %TEMP%\ayrac-build\dict\en-tr.sqlite (or AYRAC_DICT).
# Keep this script ASCII: Windows PowerShell 5 reads it with the ANSI code page.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = Get-ChildItem (Join-Path $env:USERPROFILE 'Tools') -Directory -Filter 'jdk-17*' | Select-Object -First 1 -ExpandProperty FullName
$env:ANDROID_HOME = Join-Path $env:USERPROFILE 'Android\Sdk'
$gradle = Join-Path $env:USERPROFILE 'Tools\gradle-8.11.1\bin\gradle.bat'
[IO.File]::WriteAllText((Join-Path $here 'local.properties'), 'sdk.dir=' + $env:ANDROID_HOME.Replace('\', '\\'), (New-Object Text.UTF8Encoding $false))
Push-Location $here
try {
  & $gradle --no-daemon -q testReleaseUnitTest
  $code = $LASTEXITCODE
} finally { Pop-Location }
$report = Join-Path $here 'app\build\test-results\testReleaseUnitTest'
Get-ChildItem $report -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
  $x = [xml](Get-Content -Raw $_.FullName)
  $s = $x.testsuite
  "{0}: {1} tests, {2} failures, {3} errors, {4} skipped" -f $s.name, $s.tests, $s.failures, $s.errors, $s.skipped
}
if ($code -ne 0) { throw "Android unit tests failed ($code); report: app\build\reports\tests\testReleaseUnitTest\index.html" }
