[CmdletBinding()]
param(
  [string]$BackupDir = (Join-Path $PSScriptRoot 'backup'),
  [int]$RetentionDays = 30
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$database = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'dajin' }
$password = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { 'root' }
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$sqlPath = Join-Path $BackupDir "dajin_backup_$stamp.sql"
$gzPath = "$sqlPath.gz"
$logPath = Join-Path $BackupDir 'backup.log'
try {
  $errPath = Join-Path $env:TEMP "dajin_dump_$stamp.err"
  docker compose exec -T mysql mysqldump -uroot "-p$password" --single-transaction --routines --events $database 1>$sqlPath 2>$errPath
  if ($LASTEXITCODE -ne 0) { throw (Get-Content -Raw $errPath) }
  Remove-Item $errPath -Force -ErrorAction SilentlyContinue
  $input = [IO.File]::OpenRead($sqlPath); $output = [IO.File]::Create($gzPath)
  try { $gzip = [IO.Compression.GZipStream]::new($output, [IO.Compression.CompressionMode]::Compress); try { $input.CopyTo($gzip) } finally { $gzip.Dispose() } } finally { $input.Dispose(); $output.Dispose() }
  Remove-Item $sqlPath -Force
  Get-ChildItem $BackupDir -Filter 'dajin_backup_*.sql.gz' | Where-Object LastWriteTime -lt (Get-Date).AddDays(-$RetentionDays) | Remove-Item -Force
  "$(Get-Date -Format s) backup created: $gzPath" | Add-Content $logPath
  Write-Output $gzPath
} catch {
  "$(Get-Date -Format s) backup failed: $($_.Exception.Message)" | Add-Content $logPath
  throw
}
