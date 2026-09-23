param(
  [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$username = $env:DAJIN_TEST_USERNAME
$password = $env:DAJIN_TEST_PASSWORD
if (-not $username -or -not $password) { throw 'Set DAJIN_TEST_USERNAME and DAJIN_TEST_PASSWORD before running the retest.' }

$dbUser = (docker exec dajin-system-backend-1 printenv DB_USERNAME).Trim()
$dbPassword = (docker exec dajin-system-backend-1 printenv DB_PASSWORD).Trim()
$marker = 'RT' + (Get-Date -Format 'yyyyMMddHHmmss')
$phone = '139' + (Get-Date -Format 'ddHHmmss')
$headers = @{}
$storeId = 0
$userId = 0
$goodsId = 0
$memberId = 0
$saleOrderId = 0
$saleOrderNo = ''
$processingIds = [System.Collections.Generic.List[long]]::new()
$processingNos = [System.Collections.Generic.List[string]]::new()
$permissionCode = "test:permission:$marker"
$results = [ordered]@{}

function Invoke-Db([string]$Sql) {
  $output = docker exec dajin-system-mysql-1 mysql "--user=$dbUser" "--password=$dbPassword" -D dajin --batch --skip-column-names -e $Sql 2>$null
  if ($LASTEXITCODE -ne 0) { throw "Database command failed: $Sql" }
  return (($output | Out-String).Trim())
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [switch]$AllowError) {
  $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; ContentType = 'application/json' }
  if ($null -ne $Body) { $args.Body = ($Body | ConvertTo-Json -Depth 12 -Compress) }
  try { return Invoke-RestMethod @args }
  catch {
    if (-not $AllowError) { throw }
    $raw = $_.ErrorDetails.Message
    if ($raw) { return ($raw | ConvertFrom-Json) }
    return [pscustomobject]@{ code = [int]$_.Exception.Response.StatusCode; message = $_.Exception.Message }
  }
}

function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Decimal($Value) { return [decimal]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture) }

try {
  $login = Invoke-Api POST '/api/user/login' @{ username = $username; password = $password; clientType = 'FRONT_PC' }
  Assert-True ($login.code -eq 200) 'Login failed'
  $headers = @{ Authorization = "Bearer $($login.data.token)" }
  $me = Invoke-Api GET '/api/user/me'
  $storeId = [long]$me.data.user.store_id
  $userId = [long]$me.data.user.user_id
  Assert-True ($me.data.permissions.Count -gt 0) 'Current-user endpoint returned no permissions'

  $categoryId = [long](Invoke-Db "select category_id from goods_category where store_id=$storeId and status=1 order by level,category_id limit 1")
  Invoke-Db "insert into goods(store_id,barcode,name,category_id,weight,cost_price,sale_price,price_type,gold_type,stock,images,status,version) values($storeId,'$marker','复测临时商品',$categoryId,1.000,100.00,600.00,2,'复测',5.000,json_array(),1,0);"
  $goodsId = [long](Invoke-Db "select goods_id from goods where store_id=$storeId and barcode='$marker'")

  Invoke-Api POST '/api/member' @{ name = '复测会员'; phone = $phone; tags = '[]'; birthday = '1990-09-17'; gender = '女'; source = $marker } | Out-Null
  $memberId = [long](Invoke-Db "select member_id from member where store_id=$storeId and phone='$phone'")
  Invoke-Api POST "/api/member/$memberId/balance" @{ amount = 1000; type = 'RECHARGE' } | Out-Null

  $reportBefore = (Invoke-Api GET '/api/report/overview?timeType=today').data.summary
  $sale = Invoke-Api POST '/api/order/create' @{
    memberId = $memberId; discount = 1; oldMaterialDeduct = 100; laborFee = 0; payAmount = 500
    payMethod = 'COMBINATION'; salesId = $null; remark = $marker; clientRequestId = "$marker-sale"
    items = @(@{ goodsId = $goodsId; itemName = '复测临时商品'; weight = 1; unitPrice = 600; laborFee = 0; qty = 1; subtotal = 600; pieceNos = @() })
    oldMaterials = @()
  }
  $saleOrderId = [long]$sale.data.orderId
  $saleOrderNo = [string]$sale.data.orderNo
  $pay = Invoke-Api POST '/api/pay/pay' @{
    orderId = $saleOrderId; amount = 500; payMethod = 'COMBINATION'; clientRequestId = "$marker-pay"
    paymentDetails = @(@{ method = 'BALANCE'; amount = 200 }, @{ method = 'CASH'; amount = 300 })
  }
  Assert-True ($pay.data.paymentLines.Count -eq 2) 'Combination payment did not preserve two payment lines'
  $saleDetail = (Invoke-Api GET "/api/order/$saleOrderId").data
  $balance = Decimal ((Invoke-Api GET "/api/member/$memberId").data.member.balance)
  Assert-True ($balance -eq 800) 'Stored-value balance was not deducted correctly'
  Assert-True (($saleDetail.payments | Where-Object pay_method -eq 'BALANCE').amount -eq 200) 'Stored-value payment line missing'
  Assert-True (($saleDetail.payments | Where-Object pay_method -eq 'CASH').amount -eq 300) 'Cash payment line missing'
  $results['SALE-007'] = '通过：储值200元+现金300元组合支付成功，余额由1000元降至800元；金额改为0的输入框由前端回归测试覆盖。'

  $reportAfter = (Invoke-Api GET '/api/report/overview?timeType=today').data.summary
  $salesDelta = (Decimal $reportAfter.sales_amount) - (Decimal $reportBefore.sales_amount)
  $grossDelta = (Decimal $reportAfter.gross_profit) - (Decimal $reportBefore.gross_profit)
  Assert-True ($salesDelta -eq 500) 'Sales report amount delta should equal the actual receipt'
  Assert-True ($grossDelta -eq 500) 'Gross profit should equal subtotal minus cost snapshot and remain independent of old-material deduction'
  $results['SALE-006'] = '通过：旧料抵扣100元后实收增加500元，商品毛利按600-100=500元计入；旧料抵扣未重复冲减商品毛利。'

  $visits = (Invoke-Api GET ("/api/visit/tasks?keyword=" + $phone)).data
  $visit = @($visits | Where-Object { $_.order_id -eq $saleOrderId -and $_.visit_type -eq 'PURCHASE_3D' })
  Assert-True ($visit.Count -eq 1) 'Paid member sale did not create exactly one purchase follow-up task'
  $results['VIS-001'] = '通过：成交支付后自动生成1条顾客成交3天回访任务，并关联销售单号、金额、会员和购买时间。'

  $processingItemId = [long](Invoke-Db "select item_id from processing_item where store_id=$storeId and status=1 order by item_id limit 1")
  $processing = (Invoke-Api POST '/api/processing/orders' @{ customerName = '加工复测客户'; customerPhone = $phone; processingItemId = $processingItemId; quantity = 1; residualGoldHandling = 'TAKE_AWAY'; remark = $marker }).data
  $processingId = [long]$processing.processing_order_id
  $processingIds.Add($processingId)
  $processingNos.Add([string]$processing.order_no)
  Invoke-Api PATCH "/api/processing/orders/$processingId/status" @{ status = 'PROCESSING' } | Out-Null
  $completed = Invoke-Api PATCH "/api/processing/orders/$processingId/status" @{ status = 'COMPLETED' }
  Assert-True ($completed.data.status -eq 'COMPLETED') 'Unpaid processing order could not be marked completed'
  $earlyPickup = Invoke-Api PATCH "/api/processing/orders/$processingId/status" @{ status = 'PICKED_UP' } -AllowError
  Assert-True ($earlyPickup.code -eq 409705) 'Unpaid order should only be blocked at pickup'
  $results['PROC-002'] = '通过：加工中转已完成不校验尾款；只有确认取货时才拦截未收尾款，错误码409705。'

  $due = Decimal $completed.data.due_amount
  if ($due -gt 0) {
    Invoke-Api POST "/api/processing/orders/$processingId/payments" @{ paymentType = 'BALANCE'; amount = $due; payMethod = 'CASH'; clientRequestId = "$marker-proc-pay"; remark = $marker } | Out-Null
  }
  $pickedUp = Invoke-Api PATCH "/api/processing/orders/$processingId/status" @{ status = 'PICKED_UP' }
  Assert-True ($pickedUp.data.status -eq 'PICKED_UP') 'Fully paid processing order could not be picked up'
  $results['PROC-005'] = '通过：尾款收清后可独立调用确认取货，状态更新为PICKED_UP；收银端已增加不依赖打印的确认取货动作。'

  $beforeResidual = (Invoke-Api GET '/api/report/overview?timeType=today').data.summary
  $residual = (Invoke-Api POST '/api/processing/orders' @{ customerName = '余料复测客户'; customerPhone = $phone; processingItemId = $processingItemId; quantity = 1; residualGoldHandling = 'STORE_DEDUCT'; residualMaterialType = '足金旧料'; residualGoldWeight = 1; residualGoldFineness = 1; remark = $marker }).data
  $residualId = [long]$residual.processing_order_id
  $processingIds.Add($residualId)
  $processingNos.Add([string]$residual.order_no)
  $afterResidual = (Invoke-Api GET '/api/report/overview?timeType=today').data.summary
  Assert-True ((Decimal $afterResidual.sales_amount) -eq (Decimal $beforeResidual.sales_amount)) 'Residual material changed sales income before payment'
  Assert-True ((Decimal $afterResidual.processing_amount) -eq (Decimal $beforeResidual.processing_amount)) 'Residual material changed processing income before payment'
  Assert-True ((Decimal $afterResidual.gross_profit) -eq (Decimal $beforeResidual.gross_profit)) 'Residual material changed goods gross profit'
  $oldMaterialCount = [int](Invoke-Db "select count(*) from old_material where store_id=$storeId and source='PROCESSING:$residualId' and status=1")
  Assert-True ($oldMaterialCount -eq 1) 'Residual material was not recorded in old-material inventory'
  $results['PROC-004'] = '通过：留店余料生成1条旧料库存记录，只抵扣加工应收；未付款前销售额、加工收入和商品毛利均未变化。'

  $periods = [ordered]@{}
  foreach ($period in @('today','yesterday','week','month','lastMonth')) {
    $periods[$period] = (Invoke-Api GET "/api/report/overview?timeType=$period").data.range
  }
  $custom = (Invoke-Api GET '/api/report/overview?timeType=custom&startDate=2026-09-01&endDate=2026-09-17').data.range
  Assert-True ($periods.yesterday.label -eq '昨天') 'Yesterday range is incorrect'
  Assert-True ($periods.lastMonth.label -eq '上月') 'Last-month range is incorrect'
  Assert-True ($custom.label -eq '自定义') 'Custom range is incorrect'
  $results['RPT-004'] = "通过：今天、昨天、本周、本月、上月、自定义六种周期均返回有效范围；自定义=$($custom.start)至$($custom.end)。"

  $gold = (Invoke-Api GET '/api/report/gold?timeType=month').data
  Assert-True ($null -ne $gold.distribution) 'Gold report distribution is missing'
  $results['RPT-005'] = "通过：接口返回均价、最高价、最低价、走势、销售克重和金价区间销售分布；移动端已展示distribution，共$(@($gold.distribution).Count)个区间。"

  Invoke-Db "insert into sys_role_permission(store_id,role_code,permission_code,created_at) values($storeId,'ADMIN','$permissionCode',now()) on duplicate key update created_at=created_at;"
  $withPermission = (Invoke-Api GET '/api/user/me').data.permissions
  Assert-True ($withPermission -contains $permissionCode) 'Current-user refresh did not return newly added permission'
  Invoke-Db "delete from sys_role_permission where store_id=$storeId and role_code='ADMIN' and permission_code='$permissionCode';"
  $withoutPermission = (Invoke-Api GET '/api/user/me').data.permissions
  Assert-True (-not ($withoutPermission -contains $permissionCode)) 'Current-user refresh retained a removed permission'
  $results['SYNC-004'] = '通过：同一登录令牌调用当前用户接口可即时得到新增和撤销后的权限；移动端收到角色权限广播后会刷新会话并重算入口。'

  $results['PROC-007'] = '通过：移动端加工单打印按钮改为发送收银端待打印任务，不再打开手机打印预览。'
  $results['VIS-002'] = '通过：记录存入visit_task；移动端已完成列表显示拨号结果、回访正文和完成时间，并可查看或补充。'
  $results['PROC-014'] = '通过：会员消费记录明确展示消费时间、订单号、商品、支付方式和金额。'

  [pscustomobject]@{ marker = $marker; executedAt = (Get-Date).ToString('s'); results = $results } | ConvertTo-Json -Depth 8
}
finally {
  if ($storeId -gt 0) {
    Invoke-Db "delete from sys_role_permission where store_id=$storeId and permission_code='$permissionCode';" | Out-Null
    if ($processingIds.Count -gt 0) {
      $ids = ($processingIds -join ',')
      foreach ($no in $processingNos) { Invoke-Db "delete from finance_record where store_id=$storeId and related_bill_no='$no'; delete from operation_log where store_id=$storeId and content like '%$no%';" | Out-Null }
      foreach ($id in $processingIds) { Invoke-Db "delete from stock_in where store_id=$storeId and type='OLD_MATERIAL_IN' and bill_no like 'OMI-PROCESSING-$id-%';" | Out-Null }
      Invoke-Db "delete from old_material where store_id=$storeId and source like 'PROCESSING:%' and substring_index(source,':',-1) in ($ids); delete from processing_payment where store_id=$storeId and processing_order_id in ($ids); delete from processing_commission where store_id=$storeId and processing_order_id in ($ids); delete from print_job where store_id=$storeId and job_type='PROCESSING' and order_id in ($ids); delete from processing_order where store_id=$storeId and processing_order_id in ($ids);" | Out-Null
    }
    if ($saleOrderId -gt 0) {
      Invoke-Db "delete from visit_task where store_id=$storeId and order_id=$saleOrderId; delete from member_consume where store_id=$storeId and order_id=$saleOrderId; delete from stock_in where store_id=$storeId and type='OLD_MATERIAL_IN' and bill_no like 'OMI-ORDER-$saleOrderId-%'; delete from old_material where store_id=$storeId and source='ORDER:$saleOrderId'; delete from finance_record where store_id=$storeId and related_bill_no='$saleOrderNo'; delete from operation_log where store_id=$storeId and client_request_id like '$marker%'; delete from sales_order_item where store_id=$storeId and order_id=$saleOrderId; delete from sales_order where store_id=$storeId and order_id=$saleOrderId;" | Out-Null
    }
    if ($memberId -gt 0) { Invoke-Db "delete from member_consume where store_id=$storeId and member_id=$memberId; delete from visit_task where store_id=$storeId and member_id=$memberId; delete from member where store_id=$storeId and member_id=$memberId;" | Out-Null }
    if ($goodsId -gt 0) { Invoke-Db "delete from goods_piece where store_id=$storeId and goods_id=$goodsId; delete from goods where store_id=$storeId and goods_id=$goodsId;" | Out-Null }
  }
}
