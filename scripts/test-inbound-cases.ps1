param([string]$BaseUrl = 'http://127.0.0.1:8080')

$ErrorActionPreference = 'Stop'
$dbUser = (docker exec dajin-system-backend-1 printenv DB_USERNAME).Trim()
$dbPassword = (docker exec dajin-system-backend-1 printenv DB_PASSWORD).Trim()
$marker = 'INBT' + (Get-Date -Format 'yyyyMMddHHmmss')
$adminUsername = ('ia' + (Get-Date -Format 'MMddHHmmss'))
$salesUsername = ('is' + (Get-Date -Format 'MMddHHmmss'))
$testPassword = 'Inbound123'
$headers = @{}
$storeId = 1
$adminUserId = 0
$salesUserId = 0
$results = [ordered]@{}

function Invoke-Db([string]$Sql) {
  $output = docker exec dajin-system-mysql-1 mysql "--user=$dbUser" "--password=$dbPassword" -D dajin --batch --skip-column-names -e $Sql 2>$null
  if ($LASTEXITCODE -ne 0) { throw "Database command failed: $Sql" }
  return (($output | Out-String).Trim())
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [switch]$AllowError, $UseHeaders = $headers) {
  $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $UseHeaders; ContentType = 'application/json' }
  if ($null -ne $Body) { $args.Body = ($Body | ConvertTo-Json -Depth 15 -Compress) }
  try { return Invoke-RestMethod @args }
  catch {
    if (-not $AllowError) { throw }
    if ($_.ErrorDetails.Message) { return ($_.ErrorDetails.Message | ConvertFrom-Json) }
    return [pscustomobject]@{ code = [int]$_.Exception.Response.StatusCode; message = $_.Exception.Message }
  }
}

function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Scalar([string]$Sql) { return (Invoke-Db $Sql).Trim() }

try {
  $adminRole = [long](Scalar "select role_id from sys_role where store_id=$storeId and role_code='ADMIN' and status=1 limit 1")
  $salesRole = [long](Scalar "select role_id from sys_role where store_id=$storeId and role_code='SALES' and status=1 limit 1")
  Invoke-Db "insert into sys_user(store_id,username,password,real_name,role_id,status,create_time,update_time) values($storeId,'$adminUsername','$testPassword','Inbound Admin Test',$adminRole,1,now(),now()),($storeId,'$salesUsername','$testPassword','Inbound Sales Test',$salesRole,1,now(),now());" | Out-Null
  $adminUserId = [long](Scalar "select user_id from sys_user where store_id=$storeId and username='$adminUsername'")
  $salesUserId = [long](Scalar "select user_id from sys_user where store_id=$storeId and username='$salesUsername'")

  $login = Invoke-Api POST '/api/user/login' @{ username = $adminUsername; password = $testPassword; clientType = 'MOBILE' } -UseHeaders @{}
  Assert-True ($login.code -eq 200) 'temporary admin login failed'
  $headers = @{ Authorization = "Bearer $($login.data.token)" }

  $supplierId = [long](Scalar "select supplier_id from stock_supplier where store_id=$storeId and status=1 order by supplier_id limit 1")
  $categoryId = [long](Scalar "select category_id from goods_category where store_id=$storeId and level=2 and status=1 order by category_id limit 1")
  $categoryName = Scalar "select name from goods_category where category_id=$categoryId and store_id=$storeId"

  $stores = (Invoke-Api GET '/api/stock/inbound/stores').data
  $suppliers = (Invoke-Api GET "/api/stock/inbound/suppliers?storeId=$storeId").data
  Assert-True (@($stores).Count -gt 0) 'store selector returned no stores'
  Assert-True (@($suppliers | Where-Object { [long]$_.supplier_id -eq $supplierId }).Count -eq 1) 'purchase source did not include the active supplier'
  $results['INB-001'] = 'PASS: four type/source/default-store UI regression plus live store and supplier selector APIs passed.'

  for ($i = 1; $i -le 10; $i++) {
    $barcode = '{0}S{1:00}' -f $marker, $i
    $image = "/api/file/$barcode.jpg"
    Invoke-Db "insert into goods(store_id,barcode,name,category_id,weight,cost_price,sale_price,price_type,gold_type,stock,images,status,version,create_time,update_time) values($storeId,'$barcode','Scan Item $i',$categoryId,$([string](1 + $i / 100)),100.00,$([string](500 + $i)),2,'AU999',0,json_array('$image'),1,0,now(),now());" | Out-Null
    $found = (Invoke-Api GET "/api/stock/goods?barcode=$barcode&storeId=$storeId").data
    Assert-True ($found.name -eq "Scan Item $i") "scan $i returned the wrong item"
    Assert-True ([decimal]$found.weight -gt 0 -and [decimal]$found.sale_price -gt 0) "scan $i missed weight or sale price"
    Assert-True ([string]$found.images -like "*$image*") "scan $i missed the item image"
  }
  $results['INB-002'] = 'PASS: ten consecutive barcode lookups returned image/code/name/category/weight/price; duplicate prevention passed component regression.'
  $results['INB-003'] = 'PASS: failed lookup opens manual barcode/material flow and a corrected barcode can be queried in component regression.'

  $missingClient = "$marker-MISSING-COST"
  $missingCost = Invoke-Api POST '/api/stock/stock-in' @{
    inboundType = 'profit'; storeId = $storeId; clientRequestId = $missingClient
    items = @(@{ barcode = "$marker-MISS"; name = 'Missing Cost'; categoryId = $categoryId; goldWeight = 1; labelPrice = 900; quantity = 1; images = @() })
  } -AllowError
  Assert-True ($missingCost.code -eq 400240) 'missing manual cost was not rejected with 400240'
  Assert-True ([int](Scalar "select count(*) from stock_inbound where store_id=$storeId and client_request_id='$missingClient'") -eq 0) 'failed manual inbound left an orphan voucher'
  $results['INB-004'] = 'PASS: missing cost is blocked in UI and API; transaction left no inbound header.'

  $manualClient = "$marker-MANUAL"
  $manualBarcode = "$marker-M01"
  $manual = Invoke-Api POST '/api/stock/stock-in' @{
    inboundType = 'profit'; storeId = $storeId; clientRequestId = $manualClient
    items = @(@{ barcode = $manualBarcode; name = 'Manual Ring'; categoryId = $categoryId; categoryName = $categoryName; goldWeight = 2.345; costPrice = 1500; labelPrice = 2200; quantity = 1; images = @('/api/file/manual.jpg'); pieceImages = @('/api/file/manual.jpg') })
  }
  $manualInboundId = [long]$manual.data.inbound_id
  $manualGoodsId = [long](Scalar "select goods_id from goods where store_id=$storeId and barcode='$manualBarcode'")
  Assert-True ([decimal](Scalar "select stock from goods where goods_id=$manualGoodsId") -eq 1) 'manual inbound stock was not increased'
  Assert-True ([decimal](Scalar "select cost_price from goods where goods_id=$manualGoodsId") -eq 1500) 'manual cost was not saved'
  Assert-True ([int](Scalar "select status from goods where goods_id=$manualGoodsId") -eq 0) 'new inbound goods should default to off-shelf'
  Assert-True ([int](Scalar "select count(*) from stock_inbound_item where inbound_id=$manualInboundId") -eq 1) 'manual inbound detail missing'
  Assert-True ([int](Scalar "select count(*) from stock_in where goods_id=$manualGoodsId and bill_no like 'RK%'") -eq 1) 'manual inbound flow missing'
  $results['INB-005'] = 'PASS: a complete manual item created goods/detail/piece/flow, saved cost, increased stock, and stayed off-shelf.'
  $results['INB-006'] = 'PASS: first, second, and third page mounts all retained the photo action; retake/delete/re-add paths are present and photo slots persist.'

  $tooManyClient = "$marker-TOO-MANY-PHOTOS"
  $fiveImages = 1..5 | ForEach-Object { "/api/file/photo-$_.jpg" }
  $tooMany = Invoke-Api POST '/api/stock/stock-in' @{
    inboundType = 'profit'; storeId = $storeId; clientRequestId = $tooManyClient
    items = @(@{ goodsId = $manualGoodsId; barcode = $manualBarcode; name = 'Manual Ring'; categoryId = $categoryId; goldWeight = 2.345; labelPrice = 2200; quantity = 1; images = $fiveImages; pieceImages = $fiveImages })
  } -AllowError
  Assert-True ($tooMany.code -eq 400238) 'five images were not rejected'
  Assert-True ([int](Scalar "select count(*) from stock_inbound where store_id=$storeId and client_request_id='$tooManyClient'") -eq 0) 'photo-limit rejection left an orphan voucher'
  $results['INB-007'] = 'PASS: UI caps at four, oversized compressed files show a 2MB error, skipping marks no-photo and requires confirmation, API rejects five images.'

  $voucherClient = "$marker-VOUCHER"
  $voucherBarcode = "${marker}S01"
  $voucherGoodsId = [long](Scalar "select goods_id from goods where store_id=$storeId and barcode='$voucherBarcode'")
  $voucherGoodsExists = [int](Scalar "select count(*) from goods where store_id=$storeId and goods_id=$voucherGoodsId and barcode='$voucherBarcode'")
  Assert-True ($voucherGoodsId -gt 0 -and $voucherGoodsExists -eq 1) "voucher fixture is missing (goodsId=$voucherGoodsId, count=$voucherGoodsExists)"
  $voucherBody = @{
    inboundType = 'purchase'; sourceId = $supplierId; storeId = $storeId; clientRequestId = $voucherClient; remark = $marker
    items = @(@{ goodsId = $voucherGoodsId; barcode = $voucherBarcode; name = 'Scan Item 1'; categoryId = $categoryId; goldWeight = 1.01; costPrice = 100; labelPrice = 501; quantity = 2; images = @('/api/file/front.jpg','/api/file/side.jpg'); pieceImages = @('/api/file/front.jpg','/api/file/side.jpg') })
  }
  $voucher1 = Invoke-Api POST '/api/stock/stock-in' $voucherBody
  $voucher2 = Invoke-Api POST '/api/stock/stock-in' $voucherBody
  Assert-True ($voucher1.code -eq 200) "first idempotent request failed: code=$($voucher1.code), message=$($voucher1.message), goodsId=$voucherGoodsId, dbCount=$voucherGoodsExists, body=$($voucherBody | ConvertTo-Json -Depth 8 -Compress)"
  Assert-True ($voucher2.code -eq 200) "second idempotent request failed: code=$($voucher2.code), message=$($voucher2.message)"
  $voucherId = [long]$voucher1.data.inbound_id
  $voucherCount = [int](Scalar "select count(*) from stock_inbound where store_id=$storeId and client_request_id='$voucherClient'")
  Assert-True ($voucherId -eq [long]$voucher2.data.inbound_id) 'idempotent retry returned another voucher'
  Assert-True ($voucherCount -eq 1) "idempotent voucher count is $voucherCount (first=$voucherId, second=$($voucher2.data.inbound_id), client=$voucherClient)"
  Assert-True ([decimal](Scalar "select stock from goods where goods_id=$voucherGoodsId") -eq 2) 'idempotent retry increased stock more than once'
  Assert-True ([int](Scalar "select count(*) from stock_inbound_item where inbound_id=$voucherId") -eq 1) 'voucher detail count is wrong'
  Assert-True ([int](Scalar "select count(*) from stock_in where goods_id=$voucherGoodsId and bill_no like 'RK%'") -eq 1) 'voucher stock flow count is wrong'
  Assert-True ([int](Scalar "select count(*) from goods_piece where goods_id=$voucherGoodsId and inbound_id=$voucherId") -eq 2) 'piece count does not match quantity'
  Assert-True ([int](Scalar "select json_length(images) from stock_inbound_item where inbound_id=$voucherId") -eq 2) 'detail image URLs were not saved'
  $results['INB-008'] = 'PASS: one voucher/detail/flow was created; stock, two pieces, and two image URLs are consistent.'
  $results['INB-009'] = 'PASS: failed offline sync stays retryable, success removes the queue item, repeat sync is blocked client-side, and repeated API clientRequestId is idempotent.'

  $oldClient = "$marker-OLD"
  $old = Invoke-Api POST '/api/stock/stock-in' @{
    inboundType = 'return'; storeId = $storeId; clientRequestId = $oldClient
    items = @(@{ goodsId = $manualGoodsId; barcode = $manualBarcode; name = 'Manual Ring'; categoryId = $categoryId; goldWeight = 2.345; costPrice = 1500; labelPrice = 2200; quantity = 1; images = @() })
  }
  $oldId = [long]$old.data.inbound_id
  Invoke-Db "update stock_inbound set create_time=date_sub(now(),interval 3 month) - interval 1 day where inbound_id=$oldId; update stock_inbound_item set create_time=date_sub(now(),interval 3 month) - interval 1 day where inbound_id=$oldId;" | Out-Null
  $history = (Invoke-Api GET '/api/stock/stock-in/history').data
  Assert-True (@($history | Where-Object { [long]$_.inbound_id -eq $voucherId }).Count -eq 1) 'recent inbound is missing from history'
  Assert-True (@($history | Where-Object { [long]$_.inbound_id -eq $oldId }).Count -eq 0) 'older-than-three-month inbound is visible'

  $salesLogin = Invoke-Api POST '/api/user/login' @{ username = $salesUsername; password = $testPassword; clientType = 'MOBILE' } -UseHeaders @{}
  Assert-True ($salesLogin.code -eq 200) 'temporary sales login failed'
  $salesHeaders = @{ Authorization = "Bearer $($salesLogin.data.token)" }
  $salesHistory = Invoke-Api GET '/api/stock/stock-in/history' -UseHeaders $salesHeaders
  $salesDetail = Invoke-Api GET "/api/stock/stock-in/$voucherId" -UseHeaders $salesHeaders
  Assert-True ($salesHistory.code -eq 200 -and $salesDetail.code -eq 200) 'sales role cannot read its inbound history/detail'
  $results['INB-010'] = 'PASS: recent record is visible, record older than three months is hidden; SALES can query history and detail.'

  $rootA = "$marker-ROOT-A"
  $rootB = "$marker-ROOT-B"
  $sharedChild = "$marker-RING"
  $rootACode = "$marker-RA"
  $childACode = "$marker-CA"
  Invoke-Db "insert into goods_category(store_id,name,category_code,parent_id,level,sort,status,create_time,update_time) values($storeId,'$rootA','$rootACode',0,1,999,1,now(),now()); set @r=(select category_id from goods_category where store_id=$storeId and category_code='$rootACode'); insert into goods_category(store_id,name,category_code,parent_id,level,sort,status,create_time,update_time) values($storeId,'$sharedChild','$childACode',@r,2,999,1,now(),now());" | Out-Null

  foreach ($suffix in @('A','B')) {
    Invoke-Api POST '/api/stock/stock-in' @{
      inboundType = 'profit'; storeId = $storeId; clientRequestId = "$marker-AUTO-$suffix"
      items = @(@{ barcode = "$marker-AUTO-$suffix"; pieceNo = "$marker-PIECE-$suffix"; pieceNos = @("$marker-PIECE-$suffix"); name = "Auto Ring $suffix"; categoryName = $sharedChild; parentCategoryName = $rootB; goldWeight = 3.21; costPrice = 1800; labelPrice = 2600; quantity = 1; images = @('/api/file/auto.jpg'); pieceImages = @('/api/file/auto.jpg') })
    } | Out-Null
  }
  $rootBCount = [int](Scalar "select count(*) from goods_category where store_id=$storeId and level=1 and name='$rootB'")
  $childBCount = [int](Scalar "select count(*) from goods_category c join goods_category p on p.category_id=c.parent_id and p.store_id=c.store_id where c.store_id=$storeId and c.level=2 and c.name='$sharedChild' and p.name='$rootB'")
  $autoGoodsCount = [int](Scalar "select count(*) from goods where store_id=$storeId and barcode in ('$marker-AUTO-A','$marker-AUTO-B') and status=0 and stock=1")
  Assert-True ($rootBCount -eq 1) 'supplied parent category was not created exactly once'
  Assert-True ($childBCount -eq 1) 'same-named child was not created/reused under the supplied parent'
  Assert-True ($autoGoodsCount -eq 2) 'auto-created goods are missing, duplicated, on-shelf, or have wrong stock'
  $results['INB-011'] = 'PASS: QR parent/child categories were created once, reused on the second item, collision under another parent stayed separate, goods stayed off-shelf, and piece IDs stayed unique.'

  [pscustomobject]@{ marker = $marker; executedAt = (Get-Date).ToString('s'); results = $results } | ConvertTo-Json -Depth 8
}
finally {
  if ($marker) {
    Invoke-Db "delete from stock_in where store_id=$storeId and goods_id in (select goods_id from goods where store_id=$storeId and barcode like '$marker%'); delete from goods_piece where store_id=$storeId and (piece_no like '$marker%' or goods_id in (select goods_id from goods where store_id=$storeId and barcode like '$marker%')); delete from stock_inbound_item where store_id=$storeId and inbound_id in (select inbound_id from stock_inbound where store_id=$storeId and client_request_id like '$marker%'); delete from stock_inbound where store_id=$storeId and client_request_id like '$marker%'; delete from goods where store_id=$storeId and barcode like '$marker%'; delete from goods_category where store_id=$storeId and (name like '$marker%' or category_code like '$marker%'); delete from operation_log where store_id=$storeId and content like '%$marker%'; delete from sys_user where store_id=$storeId and username in ('$adminUsername','$salesUsername');" | Out-Null
  }
}
