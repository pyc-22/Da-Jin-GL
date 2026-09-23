param([string]$BaseUrl = 'http://127.0.0.1:8080')

$ErrorActionPreference = 'Stop'
$dbUser = (docker exec dajin-system-backend-1 printenv DB_USERNAME).Trim()
$dbPassword = (docker exec dajin-system-backend-1 printenv DB_PASSWORD).Trim()
$marker = 'CHKTEST' + (Get-Date -Format 'yyyyMMddHHmmss')
$adminUsername = 'ca' + (Get-Date -Format 'MMddHHmmss')
$salesUsername = 'cs' + (Get-Date -Format 'MMddHHmmss')
$password = 'Check123'
$storeId = 1
$adminUserId = 0
$salesUserId = 0
$results = [ordered]@{}

function Invoke-Db([string]$Sql) {
  $output = docker exec dajin-system-mysql-1 mysql "--user=$dbUser" "--password=$dbPassword" -D dajin --batch --raw --skip-column-names -e $Sql 2>$null
  if ($LASTEXITCODE -ne 0) { throw "Database command failed: $Sql" }
  return (($output | Out-String).Trim())
}

function Scalar([string]$Sql) { return (Invoke-Db $Sql).Trim() }

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, $Headers = @{}, [switch]$AllowError) {
  $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; ContentType = 'application/json' }
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

function Login([string]$Username) {
  $response = Invoke-Api POST '/api/user/login' @{ username = $Username; password = $password; clientType = 'MOBILE' }
  Assert-True ($response.code -eq 200 -and $response.data.token) "$Username login failed"
  return @{ Authorization = "Bearer $($response.data.token)" }
}

function Submit-Check($Headers, [long]$GoodsId, [decimal]$Actual, [string]$Suffix, [string]$Remark) {
  return Invoke-Api POST '/api/stock/check' @{
    scopeType = 'GOODS'; scopeId = $GoodsId; remark = $Remark
    clientRequestId = "$marker-$Suffix"
    rows = @(@{ goodsId = $GoodsId; actual = $Actual })
  } $Headers
}

try {
  $adminRole = [long](Scalar "select role_id from sys_role where store_id=$storeId and role_code='MANAGER' and status=1 limit 1")
  $salesRole = [long](Scalar "select role_id from sys_role where store_id=$storeId and role_code='SALES' and status=1 limit 1")
  $categoryId = [long](Scalar "select category_id from goods_category where store_id=$storeId and level=2 and status=1 order by category_id limit 1")

  Invoke-Db "insert into sys_user(store_id,username,password,real_name,role_id,permission_initialized,status,create_time,update_time) values($storeId,'$adminUsername','$password','Stock Check Manager',$adminRole,0,1,now(),now()),($storeId,'$salesUsername','$password','Stock Check Sales',$salesRole,1,1,now(),now());" | Out-Null
  $adminUserId = [long](Scalar "select user_id from sys_user where store_id=$storeId and username='$adminUsername'")
  $salesUserId = [long](Scalar "select user_id from sys_user where store_id=$storeId and username='$salesUsername'")
  foreach ($permission in @('stock:check:create','stock:check:submit','stock:check:view')) {
    Invoke-Db "insert into sys_user_permission(store_id,user_id,permission_code,created_at) values($storeId,$salesUserId,'$permission',now());" | Out-Null
  }

  for ($i = 1; $i -le 3; $i++) {
    Invoke-Db "insert into goods(store_id,barcode,name,category_id,weight,cost_price,sale_price,price_type,gold_type,stock,images,status,version,create_time,update_time) values($storeId,'$marker-G$i','$marker Item $i',$categoryId,1.000,100.00,200.00,2,'TEST',10,'[]',1,0,now(),now());" | Out-Null
  }
  $goods1 = [long](Scalar "select goods_id from goods where store_id=$storeId and barcode='$marker-G1'")
  $goods2 = [long](Scalar "select goods_id from goods where store_id=$storeId and barcode='$marker-G2'")
  $goods3 = [long](Scalar "select goods_id from goods where store_id=$storeId and barcode='$marker-G3'")

  $adminHeaders = Login $adminUsername
  $salesHeaders = Login $salesUsername

  # CHK-001: submitting a difference creates a pending approval and leaves stock unchanged.
  $first = Submit-Check $salesHeaders $goods1 12 'APPROVE' '盘盈2件，待审批'
  Assert-True ($first.code -eq 200) 'CHK-001 submission failed'
  $check1 = [long]$first.data.check_id
  $approval1 = [long]$first.data.approval_id
  Assert-True ($check1 -gt 0 -and $approval1 -gt 0) 'CHK-001 did not return check and approval ids'
  Assert-True ([decimal](Scalar "select stock from goods where goods_id=$goods1") -eq 10) 'CHK-001 changed stock before approval'
  Assert-True ([int](Scalar "select status from stock_check where check_id=$check1") -eq 1) 'CHK-001 check is not pending'
  Assert-True ([int](Scalar "select status from approval where approval_id=$approval1") -eq 1) 'CHK-001 approval is not pending'
  $salesHistory = (Invoke-Api GET '/api/stock/check/history' $null $salesHeaders).data
  Assert-True (@($salesHistory | Where-Object { [long]$_.check_id -eq $check1 }).Count -eq 1) 'CHK-001 sales history missed the submitted check'
  $results['CHK-001'] = 'PASS - created a pending check/approval; stock stayed 10; sales history displayed the record.'

  # CHK-002: approval applies the stored difference once and creates an inbound ledger row.
  $approved = Invoke-Api POST "/api/approval/$approval1/approve" @{ remark = '复核通过' } $adminHeaders
  Assert-True ($approved.code -eq 200) 'CHK-002 approval failed'
  Assert-True ([decimal](Scalar "select stock from goods where goods_id=$goods1") -eq 12) 'CHK-002 stock was not adjusted to 12'
  Assert-True ([int](Scalar "select status from stock_check where check_id=$check1") -eq 3) 'CHK-002 check status was not approved'
  Assert-True ([decimal](Scalar "select qty from stock_in where store_id=$storeId and bill_no=concat((select bill_no from stock_check where check_id=$check1),'-IN-',$goods1) limit 1") -eq 2) 'CHK-002 did not create the +2 stock-in ledger'
  $repeatApprove = Invoke-Api POST "/api/approval/$approval1/approve" @{ remark = '重复审批' } $adminHeaders -AllowError
  Assert-True ($repeatApprove.code -eq 409001) 'CHK-002 repeat approval was not blocked'
  Assert-True ([decimal](Scalar "select stock from goods where goods_id=$goods1") -eq 12) 'CHK-002 repeat approval changed stock'
  $results['CHK-002'] = 'PASS - approval changed stock 10→12, wrote a +2 inbound ledger, and duplicate approval was blocked.'

  # CHK-003: rejection persists the reason and never changes stock or creates ledgers.
  $second = Submit-Check $salesHeaders $goods2 7 'REJECT' '盘亏3件，申请复核'
  $check2 = [long]$second.data.check_id
  $approval2 = [long]$second.data.approval_id
  $rejected = Invoke-Api POST "/api/approval/$approval2/reject" @{ remark = '现场数量未复核，请重新盘点' } $adminHeaders
  Assert-True ($rejected.code -eq 200) 'CHK-003 rejection failed'
  Assert-True ([decimal](Scalar "select stock from goods where goods_id=$goods2") -eq 10) 'CHK-003 rejection changed stock'
  Assert-True ([int](Scalar "select status from stock_check where check_id=$check2") -eq 4) 'CHK-003 check status was not rejected'
  $storedRejectReason = Scalar "select approve_remark from stock_check where check_id=$check2"
  $storedRejectHex = Scalar "select hex(approve_remark) from stock_check where check_id=$check2"
  $expectedRejectHex = (([Text.Encoding]::UTF8.GetBytes('现场数量未复核，请重新盘点') | ForEach-Object { $_.ToString('X2') }) -join '')
  Assert-True ($storedRejectHex -eq $expectedRejectHex) "CHK-003 rejection reason mismatch: value=[$storedRejectReason], hex=[$storedRejectHex]"
  $bill2 = Scalar "select bill_no from stock_check where check_id=$check2"
  Assert-True ([int](Scalar "select (select count(*) from stock_in where store_id=$storeId and bill_no like '$bill2-%')+(select count(*) from stock_out where store_id=$storeId and bill_no like '$bill2-%')") -eq 0) 'CHK-003 rejection created a stock ledger'
  $repeatReject = Invoke-Api POST "/api/approval/$approval2/reject" @{ remark = '重复驳回' } $adminHeaders -AllowError
  Assert-True ($repeatReject.code -eq 409001) 'CHK-003 repeat rejection was not blocked'
  $results['CHK-003'] = 'PASS - rejection kept stock at 10, saved the reason, wrote no ledger, and repeat rejection was blocked.'

  # CHK-004: sales can submit/view own checks but cannot approve or view another user's check.
  $third = Submit-Check $adminHeaders $goods3 10 'ADMIN' '管理员数据隔离样本'
  $check3 = [long]$third.data.check_id
  $approval3 = [long]$third.data.approval_id
  $salesApprove = Invoke-Api POST "/api/approval/$approval3/approve" @{ remark = '销售越权审批' } $salesHeaders -AllowError
  Assert-True ($salesApprove.code -in @(403,403001)) "CHK-004 sales approval response mismatch: code=$($salesApprove.code), message=$($salesApprove.message)"
  $salesOtherDetail = Invoke-Api GET "/api/stock/check/$check3" $null $salesHeaders -AllowError
  Assert-True ($salesOtherDetail.code -eq 404206) 'CHK-004 sales could read another user check detail'
  $salesHistory = (Invoke-Api GET '/api/stock/check/history' $null $salesHeaders).data
  Assert-True (@($salesHistory | Where-Object { [long]$_.check_id -eq $check3 }).Count -eq 0) 'CHK-004 sales history exposed another user check'
  Assert-True (@($salesHistory | Where-Object { [long]$_.check_id -in @($check1,$check2) }).Count -eq 2) 'CHK-004 sales own checks were missing'
  $results['CHK-004'] = 'PASS - sales submitted/viewed own checks, approval returned HTTP 403, and another user check stayed hidden.'

  [pscustomobject]$results | ConvertTo-Json -Depth 5
}
finally {
  if ($adminUserId -gt 0 -or $salesUserId -gt 0) {
    Invoke-Db "delete from operation_log where store_id=$storeId and (user_id in ($adminUserId,$salesUserId) or content like '%$marker%'); delete from stock_in where store_id=$storeId and goods_id in (select goods_id from goods where store_id=$storeId and barcode like '$marker%'); delete from stock_out where store_id=$storeId and goods_id in (select goods_id from goods where store_id=$storeId and barcode like '$marker%'); delete a from approval a join stock_check sc on sc.check_id=a.biz_id and a.type='STOCK_CHECK' where sc.store_id=$storeId and (sc.client_request_id like '$marker%' or sc.bill_no like '$marker%'); delete from stock_check where store_id=$storeId and client_request_id like '$marker%'; delete from sys_user_permission where store_id=$storeId and user_id in ($adminUserId,$salesUserId); delete from sys_user where store_id=$storeId and user_id in ($adminUserId,$salesUserId); delete from goods where store_id=$storeId and barcode like '$marker%';" | Out-Null
  }
}
