[CmdletBinding()]
param([string]$BackupDir = (Join-Path $PSScriptRoot 'backup'))
$ErrorActionPreference = 'Stop'
$files = @(Get-ChildItem $BackupDir -Filter 'dajin_backup_*.sql.gz' | Sort-Object LastWriteTime -Descending)
if (-not $files) { throw "No backup files found in $BackupDir" }
Write-Host 'Available backups:'
for ($i = 0; $i -lt $files.Count; $i++) { Write-Host "[$i] $($files[$i].Name) ($($files[$i].Length) bytes)" }
$selection = Read-Host 'Select backup index'
$selected = $files[[int]$selection]
if (-not $selected) { throw 'Invalid backup selection' }
if ((Read-Host "Type RESTORE to confirm $($selected.Name)") -ne 'RESTORE') { throw 'Restore cancelled' }
& (Join-Path $PSScriptRoot 'backup.ps1') -BackupDir $BackupDir | Out-Null
$database = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'dajin' }
$password = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { 'root' }
$temp = Join-Path $env:TEMP "dajin_restore_$([guid]::NewGuid()).sql"
$input = [IO.File]::OpenRead($selected.FullName); $output = [IO.File]::Create($temp)
try { $gzip = [IO.Compression.GZipStream]::new($input, [IO.Compression.CompressionMode]::Decompress); try { $gzip.CopyTo($output) } finally { $gzip.Dispose() } } finally { $input.Dispose(); $output.Dispose() }
docker compose stop --no-deps backend | Out-Host
try {
  Get-Content -Raw -Encoding utf8 $temp | docker compose exec -T mysql mysql -uroot "-p$password" $database
  if ($LASTEXITCODE -ne 0) { throw 'mysql restore failed' }
} finally {
  docker compose start backend | Out-Host
  Remove-Item $temp -Force -ErrorAction SilentlyContinue
}
$count = docker compose exec -T mysql mysql -uroot "-p$password" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database';"
Write-Host "Restore complete. Table count: $($count | Select-Object -Last 1)"
