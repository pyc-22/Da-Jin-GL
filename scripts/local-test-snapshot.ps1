#Requires -Version 7
<#
.SYNOPSIS
  本地测试数据快照：把本地开发库整库导出，供 local-test-restore.ps1 一键回滚。
.DESCRIPTION
  只操作本机 Docker 里的 dajin-system-mysql-1，不碰任何远端。
  快照与元数据写入 runtime-logs/local-test/（该目录已被 .gitignore 忽略）。
.EXAMPLE
  pwsh -File scripts/local-test-snapshot.ps1 -Label baseline
#>
param(
  [string]$Label = 'baseline'
)

$ErrorActionPreference = 'Stop'
$Container = 'dajin-system-mysql-1'
$Root = Split-Path -Parent $PSScriptRoot
$Dir = Join-Path $Root 'runtime-logs\local-test'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Base = "$Label-$Stamp"
$SqlPath = Join-Path $Dir "$Base.sql"
$MetaPath = Join-Path $Dir "$Base.meta.txt"
$Remote = '/tmp/dajin-local-test-snapshot.sql'

Write-Host "==> 导出整库（容器 $Container）" -ForegroundColor Cyan
docker exec $Container sh -c 'rm -f /tmp/dajin-local-test-snapshot.sql; mysqldump --default-character-set=utf8mb4 --single-transaction --no-tablespaces --add-drop-table --triggers --set-gtid-purged=OFF -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" > /tmp/dajin-local-test-snapshot.sql'
if ($LASTEXITCODE -ne 0) { throw 'mysqldump 失败，快照未生成' }

docker cp "${Container}:$Remote" $SqlPath | Out-Null
docker exec $Container rm -f $Remote | Out-Null

$Size = [Math]::Round((Get-Item $SqlPath).Length / 1MB, 2)

$CountSql = @'
select 'sales_order' t, count(*) c from sales_order
union all select 'sales_order_item', count(*) from sales_order_item
union all select 'approval', count(*) from approval
union all select 'goods', count(*) from goods
union all select 'goods_piece', count(*) from goods_piece
union all select 'goods_piece_occupied', count(*) from goods_piece where sales_order_id is not null
union all select 'old_material', count(*) from old_material
union all select 'operation_log', count(*) from operation_log
union all select 'sys_user', count(*) from sys_user
'@
$Counts = $CountSql | docker exec -i $Container sh -c 'mysql --default-character-set=utf8mb4 -N -B -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'

$Head = (git -C $Root rev-parse --short HEAD) 2>$null

$Meta = @()
$Meta += "snapshot   : $SqlPath"
$Meta += "takenAt    : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$Meta += "sizeMB     : $Size"
$Meta += "gitHEAD    : $Head"
$Meta += "counts:"
$Meta += ($Counts | ForEach-Object { "  $_" })
$Meta | Set-Content -Encoding UTF8 $MetaPath

Write-Host "==> 快照完成" -ForegroundColor Green
Write-Host "    SQL : $SqlPath  ($Size MB)"
Write-Host "    META: $MetaPath"
Write-Host "    回滚 : pwsh -File scripts/local-test-restore.ps1 -Snapshot `"$SqlPath`""
$Meta | ForEach-Object { Write-Host "    $_" }
