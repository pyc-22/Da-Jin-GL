[CmdletBinding()]
param(
  [string]$Source = (Join-Path $PSScriptRoot '..\db\schema.sql'),
  [Parameter(Mandatory = $true)]
  [string]$Destination
)

$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$lines = Get-Content -LiteralPath $sourcePath -Encoding utf8
$firstSeed = [Array]::FindIndex($lines, [Predicate[string]] { param($line) $line -match '^INSERT\s' })
if ($firstSeed -lt 1) { throw "Cannot find seed section in $sourcePath" }

$schema = [System.Collections.Generic.List[string]]::new()
$schema.AddRange([string[]]$lines[0..($firstSeed - 1)])
$schema.Add('')
$schema.Add('-- Production bootstrap data: no users, credentials, goods, inventory, or non-zero metal prices.')
$schema.Add("INSERT IGNORE INTO sys_store(store_id,store_name,address,status) VALUES (1,'默认门店','',1);")
$schema.Add(@'
INSERT IGNORE INTO sys_role(role_id,store_id,role_name,role_code,description,permissions,status) VALUES (1,1,'管理员','ADMIN','系统全部权限','["*"]',1),(2,1,'店长','MANAGER','门店经营及管理权限','[]',1),(3,1,'前台','CASHIER','收银、会员及交班权限','[]',1),(4,1,'销售','SALES','个人业绩、入库盘点、回收、加工、会员及回访权限','[]',1),(5,1,'打金师傅','CRAFTSMAN','加工订单承接、损耗与提成归属','[]',1);
'@)
$schema.Add(@'
INSERT IGNORE INTO sys_config(store_id,config_group,config_key,config_value,description,config_sort) VALUES (1,'SYSTEM','discount_threshold','0.85','低于此折扣需审批',1),(1,'SYSTEM','recycle_approval_limit','10000','超过此金额的大额回收需审批',2),(1,'SYSTEM','default_commission_rate','0.01','默认销售提成比例',3),(1,'SYSTEM','old_material_types','[{"name":"足金999","status":1},{"name":"足金990","status":1},{"name":"22K金","status":1},{"name":"18K金","status":1},{"name":"14K金","status":1},{"name":"铂金950","status":1},{"name":"铂金900","status":1},{"name":"纯银","status":1}]','旧料类型（库存类型管理）',4),(1,'SYSTEM','gold_metal_types','[{"name":"足金","code":"GOLD","purity":99.9,"price":0,"sort":1,"status":1},{"name":"回收金价","code":"RECYCLE","purity":99.9,"price":0,"sort":2,"status":1},{"name":"18K","code":"18K","purity":75,"price":0,"sort":3,"status":1},{"name":"铂金","code":"PLATINUM","purity":95,"price":0,"sort":4,"status":1},{"name":"银","code":"SILVER","purity":99.9,"price":0,"sort":5,"status":1},{"name":"银回收价","code":"SILVER_RECYCLE","purity":99.9,"price":0,"sort":6,"status":1}]','贵金属类型（JSON数组）',5);
'@)
$schema.Add("INSERT IGNORE INTO pay_channel(store_id,channel_name,channel_code,sort,status,icon) VALUES (1,'现金','CASH',1,1,'cash'),(1,'微信','WECHAT',2,1,'wechat'),(1,'支付宝','ALIPAY',3,1,'alipay'),(1,'银行卡','BANK',4,1,'bank'),(1,'储值','BALANCE',5,1,'balance'),(1,'组合','COMBINATION',6,1,'combination');")
$schema.Add("INSERT IGNORE INTO stock_supplier(store_id,supplier_name,supplier_code,status) VALUES (1,'默认供应商','DEFAULT_SUPPLIER',1);")
$schema.Add("INSERT IGNORE INTO goods_category(category_id,store_id,name,category_code,parent_id,level,sort,status) VALUES (1,1,'成品黄金','FINISHED_GOLD',0,1,1,1),(2,1,'K金','K_GOLD',0,1,2,1),(3,1,'银饰','SILVER',0,1,3,1),(4,1,'加工','PROCESSING',0,1,4,1);")
$schema.Add("INSERT IGNORE INTO processing_category(store_id,name,category_code,sort,status) VALUES (1,'首饰加工','JEWELRY',1,1),(1,'摆件加工','ORNAMENT',2,1),(1,'维修保养','REPAIR',3,1),(1,'定制加工','CUSTOM',4,1);")

$destinationPath = [IO.Path]::GetFullPath($Destination)
$destinationDirectory = [IO.Path]::GetDirectoryName($destinationPath)
[IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null
[IO.File]::WriteAllLines($destinationPath, $schema, [Text.UTF8Encoding]::new($false))
Write-Output $destinationPath
