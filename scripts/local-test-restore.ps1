#Requires -Version 7
<#
.SYNOPSIS
  本地测试数据一键重置：把本地开发库恢复到某个快照（默认取最新一个）。
.DESCRIPTION
  只操作本机 Docker 里的 dajin-system-mysql-1，不碰任何远端。
  注意：这是整库回滚——快照之后产生的所有改动都会消失，包括测试期间新建的账号/商品。
.EXAMPLE
  pwsh -File scripts/local-test-restore.ps1                 # 恢复最新快照
  pwsh -File scripts/local-test-restore.ps1 -Snapshot runtime-logs/local-test/baseline-20261006-140000.sql
  pwsh -File scripts/local-test-restore.ps1 -List           # 只列出可用快照
#>
param(
  [string]$Snapshot,
  [switch]$List
)

$ErrorActionPreference = 'Stop'
$Container = 'dajin-system-mysql-1'
$Root = Split-Path -Parent $PSScriptRoot
$Dir = Join-Path $Root 'runtime-logs\local-test'

if (-not (Test-Path $Dir)) { throw "没有快照目录：$Dir（先跑 scripts/local-test-snapshot.ps1）" }
$All = Get-ChildItem $Dir -Filter *.sql | Sort-Object LastWriteTime -Descending

if ($List) {
  Write-Host "可用快照（新→旧）："
  $All | ForEach-Object { "  {0}  {1,8:N2} MB  {2}" -f $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), ($_.Length/1MB), $_.Name }
  return
}
if (-not $All) { throw "没有可用的快照文件（$Dir）" }

if (-not $Snapshot) { $Snapshot = $All[0].FullName }
$Snapshot = (Resolve-Path $Snapshot).Path
if (-not (Test-Path $Snapshot)) { throw "快照不存在：$Snapshot" }

Write-Host "==> 即将把整库回滚到这个快照：" -ForegroundColor Yellow
Write-Host "    $Snapshot"
$Meta = [IO.Path]::ChangeExtension($Snapshot, '.meta.txt')
if (Test-Path $Meta) { Get-Content $Meta | ForEach-Object { Write-Host "    $_" } }

$Remote = '/tmp/dajin-local-test-restore.sql'
docker cp $Snapshot "${Container}:$Remote" | Out-Null
Write-Host "==> 执行恢复" -ForegroundColor Cyan
docker exec $Container sh -c 'mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < /tmp/dajin-local-test-restore.sql'
if ($LASTEXITCODE -ne 0) { throw '恢复失败，请检查上面的 mysql 报错' }
docker exec $Container rm -f $Remote | Out-Null

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
Write-Host "==> 恢复后行数" -ForegroundColor Green
$CountSql | docker exec -i $Container sh -c 'mysql --default-character-set=utf8mb4 -N -B -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' | ForEach-Object { Write-Host "    $_" }
Write-Host ""
Write-Host "提示：数据库已回滚，若前端出现权限/登录异常，请退出重新登录。" -ForegroundColor Yellow
