# DeepSeek 改动潜在问题审查报告

审查日期：2026-10-10  
审查范围：用户提供的审查清单所列代码改动及加工一单多件/阶梯计价相关工作区代码。  
审查方式：静态代码审查；未连接生产环境或云端数据库，未修改源码、提交、推送或部署。

> 说明：以下“P0/P1/P2”表示审查优先级。标有“需实测”的项目尚未在隔离运行态数据中验证。行号对应本次审查时工作区版本。

## P0 会导致钱/数据错误

### 1. 加工开单幂等键并发冲突，缺失键时可重复建单
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:280-284,383`
- **问题：** 相同 `clientRequestId` 并发开单时，两个请求可能同时通过预查询，后一个撞唯一键并返回 500；未携带键时可以重复建单。
- **证据：** 代码先查询 `processing_order`，随后直接插入；对 `DuplicateKeyException` 没有做幂等回放。空键会以 `NULL` 保存，唯一索引允许存在多条 `NULL`。
- **最小修法：** 用数据库唯一键竞争作为原子幂等保障，捕获冲突后按门店和请求键读取并返回原单；写接口要求调用方提供非空键。
- **验收：** 并发提交相同键、空键和断网重传；确认同一键只产生一单且响应不为 500。

### 2. 加工组合支付冲销没有按支付组成逐笔处理
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:1421-1507,1186-1210`
- **问题：** 组合支付只持久化一条 `COMBINATION` 收款与财务流水，撤回时也按 `COMBINATION` 汇总冲销，无法按现金、微信、团购等组成方式逐项核销、冲销或对账。
- **证据：** `components` 仅用于生成备注；`processing_payment` 和 `finance_record` 各插入一条汇总记录。撤回循环读取到的也是汇总支付方式。团购核销号保存在主单字段并有唯一约束，撤回没有释放或标记凭证状态。
- **最小修法：** 保存组合子流水，或以可查询、可逆的结构持久化组件；撤回、退款、报表按组件生成对应冲销，并明确团购凭证撤回后的占用规则。
- **验收：** 用“团购 + 现金”组合支付后依次验证收款明细、撤回/退款反向流水、团购凭证重用规则、报表和交班汇总。

### 3. 回收屑计价漏算店供补金
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:1371-1384,714-719`
- **问题：** 回收屑公式仅计算“融后金重 − 成品实重”，未包含店供补金；会导致回收屑抵扣、应收和返款偏差。
- **证据：** `residualDustWeight` 的参数只有融后金重、来料克重/成色、成品重量；公式为 `base.subtract(finished)`。调用处未加 `store_gold_weight`。
- **最小修法：** 按已确认口径统一计算“来料折重 + 补金克重 − 成品折重”，并将同一结果用于回收屑重量、抵扣、应收及返款。
- **验收：** 来料 20g、成色 0.999、补金 1g、成品 18g、足金回收价 900 元/g；应得到回收屑 3g、抵扣 2700 元，并核对最终应收/返款。

### 4. 有促销优惠时返款按优惠前金额计算
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:1380-1384,673-677`
- **问题：** 返款计算使用未扣促销优惠的 `grossDue`，可能少付客户返款。
- **证据：** `due = grossDue - promotionDiscount`，但 `refund = grossDue - paid` 的正差额；称重路径同样以 `billingGrossDue` 而非优惠后应收计算 `billingRefund`。
- **最小修法：** 根据扣除促销后的最终应收计算返款，且与返款审批阈值、返款登记接口采用同一口径。
- **验收：** 例如优惠前应收 100 元、优惠 20 元、已收 90 元，核对返款应为 10 元。

### 5. 销售超额旧金返款没有按门店渠道语义排除储值
- **文件/行号：** `backend/src/main/java/com/dajin/system/pay/PayController.java:190-191`
- **问题：** `CHUZHI` 等非 `BALANCE` 储值渠道可能通过返款方式校验，被记为普通费用，而非会员储值账。
- **证据：** 校验只拒绝字面值 `BALANCE`，其余渠道只检查 `pay_channel.status=1`；该路径直接插入 `finance_record`。
- **最小修法：** 使用 `PaymentChannelPolicy.isBalanceChannel(db, storeId, method)` 语义识别并拒绝储值返款方式。
- **验收：** 以启用的 `CHUZHI` 作为超额返款方式提交，确认请求被拒，会员余额与财务流水均不变化。

## P1 会导致显示/口径错误或体验问题

### 6. UTC 数据时间与上海日期边界混用
- **文件/行号：** `backend/src/main/java/com/dajin/system/report/ReportController.java:32-50,66,84-97`；`backend/src/main/java/com/dajin/system/admin/AdminController.java:49-55`；`backend/src/main/java/com/dajin/system/admin/AdminFinanceController.java:74-76`；`backend/src/main/java/com/dajin/system/processing/ProcessingWarrantyHtml.java:14`
- **问题：** 代码用 `Asia/Shanghai` 生成日期，但 SQL 对 DATETIME 使用 `date()` / `date_format()` 直接截断；财务摘要和质保单日期依赖 JVM 默认时区。UTC 存储时，上海凌晨订单可能进入错误日期或月份。
- **证据：** `ReportController` 使用上海 `LocalDate.now` 传入查询，查询再按原 DATETIME 的日期部分匹配；`AdminFinanceController` 使用无时区参数的 `LocalDate.now()`；质保单同样如此。
- **最小修法：** 统一服务器、容器与数据库时区，或按明确的上海起止时刻转换后查询，打印日期显式指定上海时区。不要在未核对历史数据前批量偏移。
- **验收（需实测）：** 在隔离环境插入 UTC `00:30` 数据（上海 `08:30`），对比首页、报表、财务汇总、工单和质保单日期。

### 7. 经营报表部分查询未排除软删除会员
- **文件/行号：** `backend/src/main/java/com/dajin/system/report/ReportController.java:84-97,105`
- **问题：** overview、sales 等查询可能继续统计关联软删除会员的销售单，影响营业额、件数、克重、排行和明细。
- **证据：** 相关销售订单查询按订单状态筛选，但没有统一关联 `member.deleted=0` 条件；同一控制器内其他查询已出现显式会员过滤，口径不一致。
- **最小修法：** 对统计与列表统一使用 `member.deleted=0`，同时兼容无会员散客订单。
- **验收：** 同期准备有效会员、软删除会员和散客订单，逐项比对首页、经营报表、员工排行与明细。

### 8. 加工单列表没有过滤软删除会员
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:424-433,1588-1593`
- **问题：** 加工订单列表仍会显示关联软删除会员的记录；详情通过 ID 也可继续读取该会员关联字段。
- **证据：** 列表和详情均对 `member` 做左连接，但没有 `m.deleted=0` 过滤。
- **最小修法：** 列表按约定过滤软删除会员；详情是否保留历史订单读取应明确历史审计需求后统一处理。
- **验收：** 软删除会员后检查加工单列表与详情，并核对历史订单的预期可见性。

### 9. 离线草稿无限重试且超量静默丢失
- **文件/行号：** `mobile/src/utils/processingSync.js:39-50`；`mobile/src/utils/pendingProcessing.js:19,55-58`
- **问题：** 永久业务错误会无限重试；待同步草稿超过 50 条时写入会截断旧数据，可能静默丢单。
- **证据：** 补传失败只增加 `tries`，没有停止策略、错误详情或用户处理入口；`writeList` 使用 `slice(0, MAX_ROWS)`。
- **最小修法：** 超容量时阻止新增并提示；保存失败原因、实现人工移除/编辑/重试和明确的失败状态，避免自动丢弃旧草稿。
- **验收（需实测）：** 填入 51 条草稿并模拟业务 4xx，确认没有静默丢失且用户能查看、处理每条记录。

### 10. 交班现金统计写死 `CASH`
- **文件/行号：** `backend/src/main/java/com/dajin/system/shift/ShiftController.java:33,61`
- **问题：** 生产现金渠道为 `XIANJIN` 时，交班现金合计会遗漏该渠道收入/支出。
- **证据：** 查询条件为 `pay_method='CASH'`；移动端已有按渠道语义匹配的实现，但后端交班 SQL 未同步。
- **最小修法：** 根据门店渠道配置/语义识别现金渠道，避免写死 code。
- **验收（需实测）：** 分别以本地 `CASH` 和生产语义 `XIANJIN` 录入同额收款，确认交班金额一致。

### 11. 前端储值过滤与手机返款选择仍使用旧交互
- **文件/行号：** `front-pc/src/App.vue:117,277`；`mobile/src/views/PendingTasks.vue:101-107`
- **问题：** 收银端返款/组合支付候选仅排除 `BALANCE`，生产 `CHUZHI` 仍可能展示；手机端返款仍通过 `window.prompt` 让员工输入支付 code。
- **证据：** 候选过滤数组只包含 `BALANCE`、`COMBINATION`；返款流程分别 `prompt` 输入金额及支付方式。后端可能拒绝部分非法选择，但 UI 仍误导用户且不符合选择面板交互。
- **最小修法：** 前端按门店渠道语义过滤储值方式；手机端提供启用渠道选择器，展示中文名称并提交真实 code。
- **验收：** 配置 `CHUZHI` 渠道后确认其不出现在返款和组合候选中；手机返款可从渠道列表选择，不要求手填 code。

### 12. 足金线遗漏：库存旧料明细与损耗订单查询
- **文件/行号：** `backend/src/main/java/com/dajin/system/stock/StockController.java:688`；`backend/src/main/java/com/dajin/system/processing/ProcessingController.java:989-992`
- **问题：** 两处使用 `重量 × 成色`，使 0.995 及以上成色没有按整克计算。
- **证据：** 旧料 SQL 生成 `weight*purity`；损耗订单 SQL 生成 `old_gold_weight*old_gold_fineness`，未使用 `Fineness.factor`。
- **最小修法：** 在 Java 层统一通过 `Fineness.weight` 计算有效重量，避免 SQL 重复实现足金线。
- **验收：** 以 0.999 和 0.994 成色数据分别验证库存有效重量、损耗明细结果。

### 13. 损耗考核分母遗漏店供补金
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:703-707`
- **问题：** 损耗率分母只含融后金重/来料折重与下料，未包含店供补金，导致考核比例偏高。
- **证据：** `lossBase` 返回 `incoming.add(downMaterialWeight)`，没有店供补金参数。
- **最小修法：** 按业务确认的考核口径把补金计入分母，并调整调用与相应测试。
- **验收：** 固定来料、损耗和下料，仅增加补金，确认损耗千分比按预期下降。

### 14. 加工写接口在业务校验前占用幂等键
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:575-578,1117-1126`（同类路径还包括 `store-gold`、`notify` 等）
- **问题：** 请求先写幂等操作日志，再做状态/业务校验；首次请求因业务错误失败后，重试同一 key 会被当作成功回放，调用方得到当前详情但操作从未完成。
- **证据：** `weighing`、`changeStatus` 在验证状态和参数前调用 `claimProcessingWrite`；该 claim 写入与后续同事务，业务异常回滚一般会一并撤销，但需核对所有异常捕获/返回路径及同事务配置。若异常被吞并提交，会留下误占键风险。
- **最小修法：** 审查所有 claim 路径是否保证异常回滚；将成功结果与幂等记录原子提交，失败不占键，并校验重放键与订单/操作一致。
- **验收（需实测）：** 对已取货订单重复提交称重、对不可流转状态重复提交状态变更；确认每次都返回原业务错误，不出现假成功回放。

## P2 洁癖/死代码

### 15. 草稿函数重复实现
- **文件/行号：** `mobile/src/utils/pendingProcessing.js:40-86`
- **问题：** 通用草稿函数与加工专用函数重复维护相同的读写逻辑，后续修复可能产生分叉。
- **最小修法：** 统一调用 `DRAFT_KINDS` 对应的通用实现。

### 16. 加工控制器职责过度集中
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:30-2069`
- **问题：** 单控制器同时负责目录、订单、定价、支付、库存、通知和提成，SQL 与业务规则耦合，跨需求修改风险高。
- **最小修法：** 后续按领域拆分应用服务/仓储；不是当前金额缺陷的直接修复项。

### 17. 多端保留独立计价展示逻辑
- **文件/行号：** `backend/src/main/java/com/dajin/system/processing/ProcessingController.java:2046-2066`；`mobile/src/views/ProcessingOrders.vue:281`；`front-pc/src/App.vue:822,878,1440,1508`
- **问题：** 多端仍计算或预览加工工费，和后端计价入口重复；规则变化时可能产生显示不一致。
- **最小修法：** 后端返回报价、工费与计价说明，客户端只展示服务端结果。

### 18. `git diff --check` 有空白问题
- **文件/行号：** `docs/待修问题-现状核对.md:3-6,144`；`front-pc/src/cashier.js:2-6`；`mobile/src/utils/processingSync.js:54`
- **问题：** 差异包含尾随空格与文件末尾空行。
- **最小修法：** 在后续编辑时清理并重新运行 `git diff --check`。

## 我确认没问题的部分

- `PaymentChannelPolicy.isBalanceChannel` 能识别 `BALANCE`、`CHUZHI` 等 code，并可通过渠道名称识别储值语义；主要支付和加工收款路径已有调用。
- `ProcessingPricing.validate` 覆盖算法合法性、阶梯重叠、累进连续覆盖与最高档无限上限。
- `mobile/src/utils/storage.js` 对订单草稿按门店和账号隔离。
- `processingSync` 有模块级并发锁；Service Worker 对 API 不缓存、导航采用网络优先并提供离线入口回退。
- `SchemaCompatibilityMigration.java` 和 `db/schema.sql` 均含加工子项、阶梯表与 `melt_weight`；旧单回填用左连接避免重复插入。

## 本轮边界与工作区状态

- 本报告仅基于代码静态审查；没有运行数据库端到端用例，也没有连接生产或云端数据库。
- 未修改源码、未提交、未推送、未部署。
- 审查前工作区已有 `admin-web/src/views/GoldPrice.vue` 修改；报告生成后另有未跟踪 `.review-diff.txt`。两者均予以保留。
