[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime = Join-Path $root 'runtime-logs/local-acceptance-20260921'
$mysql = Join-Path $root '.build-cache/mysql-test-runtime/mysql-8.4.0-winx64/bin/mysql.exe'
$java = 'D:\jdk17\bin\java.exe'
$node = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$database = 'dajin_manual_20260921'
$mysqlArgs = @('--protocol=TCP', '--host=127.0.0.1', '--port=13306', '--user=root', '--skip-password', '--default-character-set=utf8mb4', '--batch', '--skip-column-names')

foreach ($binary in @($mysql, $java, $node)) {
  if (!(Test-Path -LiteralPath $binary)) { throw "Missing local runtime: $binary" }
}
foreach ($port in @(8080,18081,18173,18174,18175)) {
  if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $port is already in use. Existing services were not changed."
  }
}
$existing = & $mysql @mysqlArgs "--execute=SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$database';"
if ($LASTEXITCODE -ne 0) { throw 'Isolated test MySQL at 127.0.0.1:13306 must be running.' }
if (!$existing) {
  & $mysql @mysqlArgs "--execute=CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated manual test database.' }
  $source = Get-Content -LiteralPath (Join-Path $root 'db/schema.sql') -Encoding utf8
  $directives = @($source | Where-Object { $_ -match '^(CREATE DATABASE|USE)\s' })
  if ($directives.Count -ne 2 -or $directives[1] -ne 'USE dajin;') {
    throw 'Unexpected database directives in the source schema; inspect before importing.'
  }
  $oldEncoding = $OutputEncoding
  try {
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    $source | Where-Object { $_ -notmatch '^(CREATE DATABASE|USE)\s' } | & $mysql @mysqlArgs "--database=$database"
    if ($LASTEXITCODE -ne 0) { throw 'Manual test schema import failed.' }
    Get-Content -LiteralPath (Join-Path $runtime 'seed.sql') -Encoding utf8 | & $mysql @mysqlArgs "--database=$database"
    if ($LASTEXITCODE -ne 0) { throw 'Manual test fixture import failed.' }
  } finally { $OutputEncoding = $oldEncoding }
} else {
  Write-Output 'Reusing manual test data without resetting orders, stock or balances.'
}

$env:DB_URL = "jdbc:mysql://127.0.0.1:13306/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = ''
$env:JWT_SECRET = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:MINIO_ENDPOINT = 'http://127.0.0.1:19000'
$env:MINIO_ACCESS_KEY = 'localtest'
$env:MINIO_SECRET_KEY = 'localtest-object-storage-not-started'
$env:MINIO_BUCKET = 'local-acceptance'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '16379'
$env:REDIS_PASSWORD = ''
$env:MINIO_CORS_ORIGINS = 'http://127.0.0.1:18173,http://127.0.0.1:18174,http://127.0.0.1:18175,http://localhost:18173,http://localhost:18174,http://localhost:18175,http://localhost:5173,http://localhost:5174,http://localhost:5175'
$env:MYSQLDUMP_PATH = Join-Path (Split-Path $mysql -Parent) 'mysqldump.exe'
$backend = Start-Process -FilePath $java -ArgumentList @(
  '-jar', (Join-Path $root 'backend/target/backend-1.0.0-rc.1.jar'),
  '--server.address=127.0.0.1', '--server.port=18081',
  '--spring.datasource.password=', '--management.health.redis.enabled=false',
  '--management.endpoint.health.group.local-core.include=db,diskSpace,ping'
) -WorkingDirectory $runtime -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend.err')
$web = $null
try {
  $ready = $false
  for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($backend.HasExited) { throw "Backend exited; inspect $runtime/backend.log" }
    try {
      $health = Invoke-RestMethod 'http://127.0.0.1:18081/actuator/health/local-core' -TimeoutSec 2
      if ($health.status -eq 'UP') { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
  }
  if (!$ready) { throw 'Local core backend did not become healthy.' }
  $web = Start-Process -FilePath $node -ArgumentList (Join-Path $runtime 'servers.mjs') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'web.log') -RedirectStandardError (Join-Path $runtime 'web.err')
  foreach ($port in @(18173,18174,18175)) {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
      if ($web.HasExited) { throw "Web service exited; inspect $runtime/web.err" }
      try {
        $response = Invoke-WebRequest "http://127.0.0.1:$port" -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $ready = $true; break }
      } catch { Start-Sleep -Seconds 1 }
    }
    if (!$ready) { throw "Local page did not start on $port" }
  }
  [ordered]@{
    database = $database; mysqlPort = 13306
    backendPid = $backend.Id; webPid = $web.Id
    cashier = 'http://127.0.0.1:18173'; admin = 'http://127.0.0.1:18174'; mobile = 'http://127.0.0.1:18175'
    mode = 'core-business-only'; uploadsAvailable = $false; cloud = $false
  } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'services.json') -Encoding utf8
  Write-Output 'Ready: cashier http://127.0.0.1:18173; admin http://127.0.0.1:18174; mobile http://127.0.0.1:18175'
} catch {
  if ($web -and !$web.HasExited) { Stop-Process -Id $web.Id }
  if (!$backend.HasExited) { Stop-Process -Id $backend.Id }
  throw
}
