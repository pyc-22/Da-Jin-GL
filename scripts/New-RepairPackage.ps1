[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^repair-\d{8}-\d{4}$')]
  [string]$ReleaseId,
  [Parameter(Mandatory = $true)]
  [string]$Python
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releases = Join-Path (Split-Path $root -Parent) 'releases'
$destination = Join-Path $releases "dajin-system-$ReleaseId"
$archive = "$destination.zip"
$cashier = Join-Path $root "front-pc/release/$ReleaseId"
$smoke = Join-Path $root 'runtime-logs/repair-20260921-cashier-package-smoke'

foreach ($path in @($destination, $archive, "$archive.sha256")) {
  if (Test-Path -LiteralPath $path) { throw "Preserve existing release: $path" }
}

$smokeResult = Get-Content -LiteralPath (Join-Path $smoke 'report.json') -Raw | ConvertFrom-Json
$expectedAsar = [IO.Path]::GetFullPath((Join-Path $cashier 'win-unpacked/resources/app.asar'))
if (!$smokeResult.passed -or $smokeResult.archive -ne $expectedAsar) {
  throw 'The selected cashier ASAR must pass its isolated startup smoke test.'
}
$installers = @(Get-ChildItem -LiteralPath $cashier -Filter '*.exe' -File)
if ($installers.Count -ne 1) { throw 'Expected exactly one new cashier installer.' }

# Export only test outcomes, never Surefire environment properties or process logs.
$suites = @(Get-ChildItem -Path (Join-Path $root 'backend/target/surefire-reports/TEST-*.xml') | ForEach-Object {
  $suite = ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite
  [pscustomobject]@{
    name = $suite.name; tests = [int]$suite.tests; errors = [int]$suite.errors
    failures = [int]$suite.failures; skipped = [int]$suite.skipped
  }
})
if (!$suites.Count -or @($suites | Where-Object { $_.errors -or $_.failures -or $_.skipped }).Count) {
  throw 'Backend tests must pass without skipped tests.'
}
$mysql = @($suites | Where-Object { $_.name -eq 'com.dajin.system.RepairMysqlTests' })
if ($mysql.Count -ne 1 -or $mysql[0].tests -lt 21) { throw 'Missing real MySQL regression coverage.' }

$frontend = @{}
foreach ($name in @('front-pc', 'mobile', 'admin-web')) {
  $report = Get-Content -LiteralPath (Join-Path $root "runtime-logs/repair-20260921-$name-tests.json") -Raw | ConvertFrom-Json
  if (!$report.success -or !$report.numTotalTests -or $report.numFailedTests -or $report.numPendingTests) {
    throw "Frontend tests did not pass: $name"
  }
  $frontend[$name] = [pscustomobject]@{
    tests = $report.numTotalTests; passed = $report.numPassedTests
    files = @($report.testResults).Count
  }
}

$deployment = & $Python (Join-Path $root 'scripts/test-deployment-config.py') -v 2>&1
if ($LASTEXITCODE -ne 0) { throw "Deployment checks failed: $deployment" }

function Copy-ReleaseFile([string]$Source, [string]$RelativeDestination) {
  $target = Join-Path $destination $RelativeDestination
  [IO.Directory]::CreateDirectory((Split-Path $target -Parent)) | Out-Null
  Copy-Item -LiteralPath $Source -Destination $target
}

[IO.Directory]::CreateDirectory($destination) | Out-Null
Copy-ReleaseFile $installers[0].FullName ("cashier/" + $installers[0].Name)
Copy-ReleaseFile (Join-Path $root 'backend/target/backend-1.0.0-rc.1.jar') 'server/backend/backend-1.0.0-rc.1.jar'
Copy-ReleaseFile (Join-Path $root 'docker-compose.release.yml') 'server/docker-compose.yml'
Copy-ReleaseFile (Join-Path $root '.env.production.example') 'server/.env.production.example'
foreach ($name in @('backend', 'admin-web', 'mobile')) {
  Copy-ReleaseFile (Join-Path $root "$name/Dockerfile.release") "server/$name/Dockerfile"
}
foreach ($name in @('admin-web', 'mobile')) {
  Copy-ReleaseFile (Join-Path $root "$name/nginx.conf") "server/$name/nginx.conf"
  $dist = if ($name -eq 'mobile') { 'mobile/dist/build/h5' } else { 'admin-web/dist' }
  Copy-Item -LiteralPath (Join-Path $root $dist) -Destination (Join-Path $destination "server/$name/dist") -Recurse
}
& (Join-Path $PSScriptRoot 'New-ProductionSchema.ps1') -Destination (Join-Path $destination 'server/db/schema.sql') | Out-Null
Copy-ReleaseFile (Join-Path $root 'docs/repair-20260921.md') 'docs/repair-20260921.md'
foreach ($name in @('report.json', 'window.png')) {
  Copy-ReleaseFile (Join-Path $smoke $name) "verification/cashier-smoke/$name"
}
foreach ($name in @('front-pc', 'mobile', 'admin-web')) {
  Copy-ReleaseFile (Join-Path $root "runtime-logs/repair-20260921-$name-tests.json") "verification/$name-tests.json"
}
$suites | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'verification/backend-tests.json') -Encoding utf8
$deployment | ForEach-Object { "$_" } | Set-Content -LiteralPath (Join-Path $destination 'verification/deployment-tests.txt') -Encoding utf8
[ordered]@{
  release = $ReleaseId
  createdAt = (Get-Date).ToUniversalTime().ToString('o')
  backendTests = ($suites | Measure-Object tests -Sum).Sum
  mysqlTests = $mysql[0].tests
  frontend = $frontend
  cashierSmokePassed = $smokeResult.passed
  cloudDeployed = $false
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'verification/summary.json') -Encoding utf8

@"
# Local repair package: $ReleaseId

Local validation and packaging only. Nothing has been uploaded or deployed.

- cashier/: new Windows x64 NSIS installer, verified from its actual app.asar.
- server/: fresh backend JAR, admin web and mobile H5 builds, release Dockerfiles,
  compose configuration and credential-free production bootstrap schema.
- verification/: backend/frontend results and isolated Electron startup evidence.
- docs/repair-20260921.md: repairs, functional impact and validation limitations.
- SHA256SUMS.txt: SHA-256 checksums for every payload file.

The installer retains application version 1.0.0-rc.1; identify this repair by the
release directory and checksums. Do not substitute an older installer.
The installer is not code-signed; Windows may show publisher warnings.
The mobile payload is H5 only. An updated Android APK is not included.

No live .env, business data, backups, credentials or signing keys are included.
The schema is for empty-database initialization, NOT a production data restore.
Existing databases are upgraded by the backend compatibility migration.
Historical stock/balance anomalies are not automatically reconciled by this fix.
Before any later deployment, back up production and review configuration and
migration behavior. No deployment command is run by the packaging script.

Startup smoke testing used an isolated local profile without a backend login.
Printer hardware, payment providers and Linux container runtime remain untested.
"@ | Set-Content -LiteralPath (Join-Path $destination 'README.md') -Encoding utf8

$manifest = @(Get-ChildItem -LiteralPath $destination -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
  if (($_.Name -like '.env*' -and $_.Name -ne '.env.production.example') -or
      $_.Name -in @('signing.json', 'local.properties', 'keystore.properties') -or
      $_.Extension -in @('.jks', '.keystore', '.p12', '.key', '.pem')) {
    throw "Forbidden release file: $($_.FullName)"
  }
  $relative = [IO.Path]::GetRelativePath($destination, $_.FullName).Replace('\', '/')
  "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $relative"
})
$manifest | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt') -Encoding utf8

Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($destination, $archive, [IO.Compression.CompressionLevel]::Optimal, $true)
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
  $expected = @{}
  foreach ($file in (Get-ChildItem -LiteralPath $destination -Recurse -File -Force)) {
    $relative = [IO.Path]::GetRelativePath($releases, $file.FullName).Replace('\', '/')
    $expected[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
  }
  foreach ($entry in $zip.Entries) {
    if ($entry.FullName.EndsWith('/')) { continue }
    if (!$expected.ContainsKey($entry.FullName)) { throw "Unexpected ZIP entry: $($entry.FullName)" }
    $stream = $entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $sha.Dispose(); $stream.Dispose() }
    if ($actual -ne $expected[$entry.FullName]) { throw "ZIP checksum mismatch: $($entry.FullName)" }
    $expected.Remove($entry.FullName)
  }
  if ($expected.Count) { throw 'ZIP is missing payload files.' }
} finally { $zip.Dispose() }
$checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
"$checksum  $([IO.Path]::GetFileName($archive))" | Set-Content -LiteralPath "$archive.sha256" -Encoding ascii
Write-Output "Verified archive: $archive"
Write-Output "SHA256: $checksum"
