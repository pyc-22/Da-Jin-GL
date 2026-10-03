package com.dajin.system;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.order.OrderController;
import com.dajin.system.pay.PayController;
import com.dajin.system.approval.ApprovalController;
import com.dajin.system.recycle.RecycleController;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.*;
import com.dajin.system.commission.CommissionController;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DAJIN_TEST_MYSQL", matches = "true")
class RepairMysqlTests {
    static com.zaxxer.hikari.HikariDataSource source;
    static JdbcTemplate jdbc;
    DbSupport db;
    OrderController orders;
    PayController payments;
    ApprovalController approvals;
    StockController stock;
    MockHttpServletRequest request;
    SyncWebSocketHandler ws;

    @BeforeAll static void schema() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ROOT")).setLevel(ch.qos.logback.classic.Level.WARN);
        source = new com.zaxxer.hikari.HikariDataSource();
        source.setJdbcUrl("jdbc:mysql://127.0.0.1:13306/dajin_repair_test?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai");
        source.setUsername("root");
        source.setPassword("");
        source.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(source);
        assertEquals("dajin_repair_test", jdbc.queryForObject("select database()", String.class));
        for (String line : Files.readAllLines(Path.of("../db/schema.sql")))
            if (line.startsWith("CREATE TABLE")) jdbc.execute(line);
        var migration = new com.dajin.system.config.SchemaCompatibilityMigration(jdbc);
        migration.run();
        migration.run();
    }

    @AfterAll static void closeDatabase() { if (source != null) source.close(); }

    @BeforeEach void setup() {
        for (String table : jdbc.queryForList("show tables", String.class)) jdbc.execute("delete from `" + table + "`");
        jdbc.update("insert into sys_store(store_id,store_name) values(1,'Test store')");
        jdbc.update("insert into sys_role(role_id,store_id,role_name,role_code) values(1,1,'Admin','ADMIN')");
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id) values(1,1,'test','test','Test',1)");
        jdbc.update("insert into sys_role(role_id,store_id,role_name,role_code) values(7,1,'Sales','SALES')");
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id) values(7,1,'sales','test','Sales',7)");
        jdbc.update("insert into sys_config(store_id,config_key,config_value) values(1,'discount_threshold','0.8'),(1,'default_commission_rate','0.01'),(1,'current_shift_no','SHIFT-TEST')");
        jdbc.update("insert into pay_channel(store_id,channel_name,channel_code) values(1,'Cash','CASH'),(1,'Stored value','BALANCE')");
        jdbc.update("insert into goods_category(category_id,store_id,name,category_code,level) values(1,1,'Items','ITEMS',2)");
        jdbc.update("insert into member(member_id,store_id,name,phone,balance) values(1,1,'Member','13800000000',1000)");
        jdbc.update("insert into goods(goods_id,store_id,barcode,name,category_id,price_type,stock,cost_price,sale_price) values(1,1,'ITEM1','Item',1,2,1,20,100)");
        db = new DbSupport(new NamedParameterJdbcTemplate(source));
        ws = mock(SyncWebSocketHandler.class);
        var shifts = new ShiftService(db);
        var old = new OldMaterialLedgerService(db);
        orders = transactional(new OrderController(db, ws, new ObjectMapper()));
        payments = transactional(new PayController(db, ws, shifts, old, new ObjectMapper()));
        approvals = transactional(new ApprovalController(db, ws, new RecycleController(db, ws, shifts, old), old, new ObjectMapper()));
        stock = transactional(new StockController(db, ws, new ObjectMapper(), old));
        request = new MockHttpServletRequest();
        request.setAttribute("storeId", 1L);
        request.setAttribute("claims", Jwts.claims(Map.of("role", "ADMIN", "storeId", 1L)).setSubject("1"));
        request.setAttribute("permissions", Set.of("*"));
    }

    @SuppressWarnings("unchecked") <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    OrderController.Req sale(String key) {
        return new OrderController.Req(1L, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, 7L, "",
                List.of(new OrderController.Item(1L, "Item", null, new BigDecimal("100"), BigDecimal.ZERO, 1, new BigDecimal("100"), List.of())), List.of(), key, null, false);
    }
    long create(String key) { return ((Number)((Map<?,?>)orders.create(sale(key), request).data()).get("orderId")).longValue(); }
    void pay(long id, String key) { payments.pay(Map.of("orderId", id, "clientRequestId", key, "amount", 100, "payMethod", "BALANCE"), request); }
    void refund(long id) {
        jdbc.update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status) values(1,'REFUND',?,1,100,'test',1)", id);
        long approval = jdbc.queryForObject("select max(approval_id) from approval", Long.class);
        approvals.approve(approval, Map.of(), request);
    }
    BigDecimal balance() { return jdbc.queryForObject("select balance from member where member_id=1", BigDecimal.class); }
    int inventory() { return jdbc.queryForObject("select stock from goods where goods_id=1", Integer.class); }

    @Test void managerReportingMigrationAppliesDefaultsOnceAndPreservesLaterEdits() throws Exception {
        jdbc.update("insert into sys_role(role_id,store_id,role_name,role_code,permissions,permission_initialized) values(2,1,'Manager','MANAGER','[\"*\"]',1)");
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id,permission_initialized,permission_customized) values(2,1,'manager','test','Manager',2,1,1)");
        jdbc.update("insert into sys_role_permission(store_id,role_code,permission_code) values(1,'MANAGER','*'),(1,'ADMIN','*')");
        jdbc.update("insert into sys_user_permission(store_id,user_id,permission_code) values(1,2,'*')");
        var migration = transactional(new com.dajin.system.config.ReportingPermissionMigration(jdbc));
        migration.run();
        var permissions = new com.dajin.system.config.PermissionService(db.jdbc());
        Set<String> manager = permissions.userPermissions(2L, 1L, "MANAGER");
        assertFalse(manager.contains("*"));
        assertFalse(manager.contains("report:store-performance"));
        assertFalse(manager.contains("report:monthly"));
        assertTrue(manager.containsAll(Set.of("report:daily","report:commission","report:processing","report:recycle")));
        assertTrue(permissions.permissions(1L,"ADMIN").contains("*"));
        jdbc.update("delete from sys_role_permission where store_id=1 and role_code='MANAGER' and permission_code='report:recycle'");
        jdbc.update("insert into sys_role_permission(store_id,role_code,permission_code) values(1,'MANAGER','report:store-performance')");
        new com.dajin.system.config.SchemaCompatibilityMigration(jdbc).run();
        migration.run();
        Set<String> later = permissions.permissions(1L,"MANAGER");
        assertFalse(later.contains("report:recycle"));
        assertTrue(later.contains("report:store-performance"));
        assertFalse(later.contains("report:monthly"));
    }

    @Test void approvalPaymentDoesNotChangeRecycleSalesOwnership() {
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id) values(2,1,'sales-a','test','Sales A',7),(3,1,'sales-b','test','Sales B',7)");
        jdbc.update("insert into sys_config(store_id,config_key,config_value) values(1,'recycle_approval_limit','1')");
        var recycle = transactional(new RecycleController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        request.setAttribute("claims",Jwts.claims(Map.of("role","SALES","storeId",1L)).setSubject("2"));
        Map<?,?> created = (Map<?,?>) recycle.create(Map.of("weight",1,"purity",1,"recyclePrice",580,"payMethod","CASH"),request).data();
        long id = ((Number)created.get("recycleOrderId")).longValue();
        request.setAttribute("claims",Jwts.claims(Map.of("role","ADMIN","storeId",1L)).setSubject("1"));
        request.setAttribute("permissions",Set.of("*"));
        approvals.approve(((Number)created.get("approvalId")).longValue(),Map.of(),request);
        assertEquals(2L,jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=?",Long.class,id));
        assertEquals(1L,jdbc.queryForObject("select operator_id from finance_record where category='RECYCLE'",Long.class));
        request.setAttribute("claims",Jwts.claims(Map.of("role","SALES","storeId",1L)).setSubject("2"));
        assertEquals(1,((List<?>)recycle.list(request).data()).size());
        var report = new com.dajin.system.report.ReportController(db);
        assertEquals(new BigDecimal("580.00"),((Map<?,?>)report.recycle(null,null,request).data()).get("amount"));
        request.setAttribute("claims",Jwts.claims(Map.of("role","SALES","storeId",1L)).setSubject("3"));
        assertTrue(((List<?>)recycle.list(request).data()).isEmpty());
        assertEquals(BigDecimal.ZERO.setScale(2),((Map<?,?>)report.recycle(null,null,request).data()).get("amount"));
    }

    @Test void historicalRecycleCreatorBackfillIsConservativeAndIdempotent() {
        for(int id=100;id<=105;id++)jdbc.update("insert into recycle_order(recycle_order_id,store_id,bill_no,weight,purity,created_by) values(?,1,?,1,1,?)",id,"HS-"+id,id==104?99L:null);
        jdbc.update("insert into approval(store_id,type,biz_id,applicant_id,status) values(1,'RECYCLE',100,2,3),(1,'RECYCLE',102,2,3),(2,'RECYCLE',105,2,3)");
        jdbc.update("insert into operation_log(store_id,user_id,module,action,content) values(1,3,'RECYCLE','CREATE','HS-101'),(1,3,'RECYCLE','CREATE','HS-102')");
        jdbc.update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id) values(1,'EXPENSE','RECYCLE',1,'CASH','HS-103',2)");
        var migration = new com.dajin.system.config.SchemaCompatibilityMigration(jdbc);
        migration.run();
        migration.run();
        assertEquals(2L,jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=100",Long.class));
        assertEquals(3L,jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=101",Long.class));
        assertNull(jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=102",Long.class));
        assertNull(jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=103",Long.class));
        assertEquals(99L,jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=104",Long.class));
        assertNull(jdbc.queryForObject("select created_by from recycle_order where recycle_order_id=105",Long.class));
    }

    @Test void partialPaymentIsRejectedWithoutMutatingOrderOrInventory() {
        long id = create("partial-sale");
        BusinessException error = assertThrows(BusinessException.class, () -> payments.pay(Map.of(
                "orderId", id, "clientRequestId", "partial-40", "amount", 40,
                "settlementMode", "PARTIAL", "payMethod", "CASH"), request));
        assertEquals(400102, error.getCode());
        assertEquals(0, jdbc.queryForObject("select status from sales_order where order_id=?", Integer.class, id));
        assertEquals(new BigDecimal("0.00"), jdbc.queryForObject("select pay_amount from sales_order where order_id=?", BigDecimal.class, id));
        assertEquals(1, inventory());
        assertEquals(0, jdbc.queryForObject("select count(*) from stock_out where type='SALE'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from finance_record where category='SALE'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from operation_log where module='PAY'", Integer.class));
        Map<?,?> last = (Map<?,?>) payments.pay(Map.of("orderId", id, "clientRequestId", "full-100", "amount", 100,
                "settlementMode", "FULL", "payMethod", "CASH"), request).data();
        assertEquals(1, last.get("status"));
        assertEquals(new BigDecimal("100.00"), last.get("actualPaid"));
        assertEquals(0, ((BigDecimal)last.get("remaining")).signum());
        assertEquals(0, inventory());
        assertEquals(1, jdbc.queryForObject("select count(*) from finance_record where category='SALE'", Integer.class));
        assertEquals(new BigDecimal("100.00"), jdbc.queryForObject("select sum(amount) from finance_record where category='SALE'", BigDecimal.class));
    }

    @Test void cashierNegotiatedPriceSettlesWithoutApprovalAndNotifiesManagersOnce() {
        jdbc.update("insert into sys_role(role_id,store_id,role_name,role_code) values(2,1,'Manager','MANAGER'),(3,1,'Cashier','CASHIER')");
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id) values(2,1,'cashier','test','Cashier',3),(3,1,'manager','test','Manager',2)");
        request.setAttribute("claims", Jwts.claims(Map.of("role", "CASHIER", "storeId", 1L)).setSubject("2"));
        long id = create("negotiated-sale");

        Map<String, Object> payment = Map.of("orderId", id, "clientRequestId", "negotiated-pay", "amount", 80,
                "settlementMode", "DISCOUNT", "settlementDiscountReason", "顾客议价", "payMethod", "CASH");
        Map<?, ?> result = (Map<?, ?>) payments.pay(payment, request).data();
        assertEquals(1, result.get("status"));
        assertEquals(new BigDecimal("80.00"), result.get("actualPaid"));
        assertEquals(new BigDecimal("20.00"), result.get("settlementDiscount"));
        assertEquals(0, jdbc.queryForObject("select count(*) from approval where biz_id=?", Integer.class, id));
        assertEquals(new BigDecimal("80.00"), jdbc.queryForObject("select sum(amount) from finance_record where category='SALE'", BigDecimal.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from operation_log where module='NOTIFICATION' and action='BARGAIN'", Integer.class));
        assertEquals(true, ((Map<?, ?>) payments.pay(payment, request).data()).get("idempotentReplay"));
        assertEquals(2, jdbc.queryForObject("select count(*) from operation_log where module='NOTIFICATION' and action='BARGAIN'", Integer.class));
    }

    @Test void paidPendingOrderNeedsRefundApprovalWithoutRestockingUnshippedGoods() {
        jdbc.update("insert into goods_piece(store_id,goods_id,piece_no) values(1,1,'PIECE1')");
        long id = create("partial-refund");
        // Legacy paid-pending orders remain refundable; new partial settlements are rejected.
        jdbc.update("update sales_order set pay_amount=40,pay_method='CASH' where order_id=?", id);
        jdbc.update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id) "
                + "select store_id,'INCOME','SALE',40,'CASH',order_no,1 from sales_order where order_id=?", id);
        BusinessException error = assertThrows(BusinessException.class, () -> orders.cancel(id, Map.of(), request));
        assertEquals(409110, error.getCode());
        assertEquals(1, inventory());
        assertEquals(2, jdbc.queryForObject("select status from goods_piece where piece_no='PIECE1'", Integer.class));
        refund(id);
        assertEquals(5, jdbc.queryForObject("select status from sales_order where order_id=?", Integer.class, id));
        assertEquals(1, inventory());
        assertEquals(1, jdbc.queryForObject("select status from goods_piece where piece_no='PIECE1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from stock_in where type='SALE_REFUND'", Integer.class));
        assertEquals(new BigDecimal("40.00"), jdbc.queryForObject("select sum(amount) from finance_record where category='SALE_REFUND'", BigDecimal.class));
    }

    @Test void aggregateOnlyStockCanPayExactlyOnceAndRefund() {
        long id = create("sale-1");
        assertEquals(1, inventory());
        pay(id, "pay-1");
        pay(id, "pay-1");
        assertEquals(0, inventory());
        assertEquals(new BigDecimal("900.00"), balance());
        refund(id);
        assertEquals(1, inventory());
        assertEquals(new BigDecimal("1000.00"), balance());
        assertThrows(BusinessException.class, () -> pay(id, "pay-again"));
        assertEquals(1, inventory());
        assertEquals(new BigDecimal("1000.00"), balance());
        assertEquals(1, jdbc.queryForObject("select count(*) from stock_out where type='SALE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from stock_in where type='SALE_REFUND'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from member_balance_record", Integer.class));
        assertEquals(new BigDecimal("0.00"), jdbc.queryForObject("select sum(amount) from member_balance_record", BigDecimal.class));
    }

    @Test void twoConcurrentOrdersCannotReserveTheLastPiece() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                String key = "concurrent-" + n;
                results.add(pool.submit(() -> { ready.countDown(); start.await(); try { create(key); return true; } catch (BusinessException e) { assertEquals(409103, e.getCode()); return false; } }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            long id = jdbc.queryForObject("select order_id from sales_order", Long.class);
            orders.cancel(id, Map.of(), request); orders.cancel(id, Map.of(), request);
            assertEquals(1, inventory());
            assertDoesNotThrow(() -> create("after-cancel"));
        } finally { pool.shutdownNow(); }
    }

    @Test void lateCreateCannotResurrectCancelledOfflineOrder() {
        orders.cancelByClient(Map.of("orderClientRequestId", "offline-1"), request);
        assertThrows(BusinessException.class, () -> create("offline-1"));
        assertEquals(0, jdbc.queryForObject("select count(*) from sales_order", Integer.class));
    }

    @Test void unsupportedDeductionCannotReduceAmountWithoutMaterial() {
        var q = sale("unbacked");
        var invalid = new OrderController.Req(q.memberId(), q.discount(), new BigDecimal("1000"), q.laborFee(), null,null,null,q.salesId(),"",q.items(),List.of(),q.clientRequestId(),null,false);
        assertThrows(BusinessException.class, () -> orders.create(invalid, request));
        assertEquals(0, jdbc.queryForObject("select count(*) from sales_order", Integer.class));
    }

    @Test void mixedRefundRestoresOnlyItsBalancePartAndRecordsCashInCurrentShift() {
        long id = create("mixed");
        payments.pay(Map.of("orderId",id,"clientRequestId","mixed-pay","amount",100,"paymentDetails",
                List.of(Map.of("method","CASH","amount",40),Map.of("method","BALANCE","amount",60))),request);
        assertEquals(new BigDecimal("940.00"),balance());
        refund(id);
        assertEquals(new BigDecimal("1000.00"),balance());
        assertEquals(new BigDecimal("40.00"),jdbc.queryForObject("select sum(amount) from finance_record where category='SALE_REFUND' and pay_method='CASH' and shift_no='SHIFT-TEST'",BigDecimal.class));
    }

    @Test void zeroCashSaleCanReturnGoods() {
        long id = create("zero");
        jdbc.update("update sales_order set old_material_deduct=100 where order_id=?",id);
        payments.pay(Map.of("orderId",id,"clientRequestId","zero-pay","amount",0),request);
        assertDoesNotThrow(() -> refund(id));
        assertEquals(1,inventory());
    }

    @Test void manualOutboundCannotConsumeAReservedItem() {
        create("reserved");
        assertThrows(BusinessException.class, () -> stock.out(Map.of("goodsId",1,"qty",1,"billNo","OUT-1"),request));
        assertEquals(1,inventory());
    }

    @Test void stockCountAdjustsTrackedPiecesTogetherWithTotalStock() {
        jdbc.update("insert into goods_piece(store_id,goods_id,piece_no) values(1,1,'PIECE1')");
        jdbc.update("insert into stock_check(store_id,bill_no,detail,status) values(1,'COUNT1','[{\"goodsId\":1,\"actual\":2,\"stockSnapshot\":1,\"difference\":1}]',1)");
        long check=jdbc.queryForObject("select check_id from stock_check",Long.class);
        jdbc.update("insert into approval(store_id,type,biz_id,status) values(1,'STOCK_CHECK',?,1)",check);
        approvals.approve(jdbc.queryForObject("select approval_id from approval",Long.class),Map.of(),request);
        assertEquals(2,inventory());
        assertEquals(2,jdbc.queryForObject("select count(*) from goods_piece where goods_id=1 and status=1",Integer.class));
    }

    @Test void recalculatingCommissionDoesNotAddItAgainAndRefundReversesIt() {
        long id=create("commission"); pay(id,"commission-pay");
        var controller=transactional(new CommissionController(db,ws));
        controller.calculate(Map.of(),request); controller.calculate(Map.of(),request);
        assertEquals(new BigDecimal("1.00"),jdbc.queryForObject("select sum(commission_amount) from commission_record",BigDecimal.class));
        refund(id);
        assertEquals(new BigDecimal("0.00"),jdbc.queryForObject("select coalesce(sum(commission_amount),0) from commission_record",BigDecimal.class).setScale(2));
    }

    @Test void commissionSnapshotSurvivesRuleAndFixedRateChangesIncludingZero() {
        long id = create("snapshot-sale");
        pay(id, "snapshot-pay");
        jdbc.update("insert into commission_rule(store_id,type,rate,`condition`) values(1,'SALE',0.5,'{\"category\":1}')");
        jdbc.update("update sys_config set config_value='0.3' where config_key='default_commission_rate'");
        var ledger = new com.dajin.system.commission.CommissionLedger(db);
        String month = java.time.LocalDate.now().toString().substring(0, 7);
        ledger.rebuild(1, month);
        assertEquals(new BigDecimal("1.00"), jdbc.queryForObject("select commission_amount from commission_record", BigDecimal.class));
        jdbc.update("update sales_order set commission_rate_snapshot=0 where order_id=?", id);
        ledger.rebuild(1, month);
        assertEquals(new BigDecimal("0.00"), jdbc.queryForObject("select commission_amount from commission_record", BigDecimal.class));
        var report = new com.dajin.system.report.ReportController(db);
        Map<?,?> record = (Map<?,?>)((List<?>)report.commission(month, request).data()).get(0);
        assertEquals(0, ((BigDecimal)record.get("commission_amount")).signum());
    }

    @Test void historicalCategoryCommissionRuleUsesOrderGoodsWithoutDuplicatingPayment() {
        long id = create("historical-rule-sale");
        pay(id, "historical-rule-pay");
        jdbc.update("update sales_order set commission_rate_snapshot=null where order_id=?", id);
        jdbc.update("insert into commission_rule(store_id,type,rate,`condition`) values(1,'SALE',0.7,'{\"category\":999}'),(1,'SALE',0.05,'{\"category\":1,\"minAmount\":50,\"maxAmount\":200}')");
        new com.dajin.system.commission.CommissionLedger(db).rebuildForOrder(1, id);
        assertEquals(new BigDecimal("5.00"), jdbc.queryForObject("select commission_amount from commission_record", BigDecimal.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from finance_record where category='SALE'", Integer.class));
    }

    @Test void salesReportQueriesStayPersonalEvenWithForgedEmployeeAndAllPermissions() {
        long own = create("own-sales-report");
        pay(own, "own-sales-pay");
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id) values(8,1,'other-sales','test','Other sales',7)");
        jdbc.update("update goods set stock=1 where goods_id=1");
        var q = sale("other-sales-report");
        var other = new OrderController.Req(q.memberId(), q.discount(), q.oldMaterialDeduct(), q.laborFee(),
                q.payAmount(), q.payMethod(), q.oldMaterialPayoutMethod(), 8L, q.remark(), q.items(), q.oldMaterials(), q.clientRequestId(), q.version(), q.handover());
        long otherId = ((Number)((Map<?,?>)orders.create(other, request).data()).get("orderId")).longValue();
        pay(otherId, "other-sales-pay");
        request.setAttribute("claims", Jwts.claims(Map.of("role", "SALES", "storeId", 1L)).setSubject("7"));
        request.setAttribute("permissions", Set.of("*", "report:view:all"));
        var controller = new com.dajin.system.report.ReportController(db);
        Map<?,?> performance = (Map<?,?>)controller.performance(8L, null, request).data();
        assertEquals(new BigDecimal("100.00"), performance.get("amount"));
        assertEquals(new BigDecimal("1.00"), performance.get("commission"));
        Map<?,?> overview = (Map<?,?>)controller.overview("month", null, null, 8L, null, request).data();
        assertEquals("PERSONAL", overview.get("scope"));
        assertEquals(new BigDecimal("100.00"), ((Map<?,?>)overview.get("summary")).get("sales_amount"));
        List<?> rows = (List<?>)((Map<?,?>)controller.sales("month", null, null, 8L, null, 1, 20, request).data()).get("records");
        assertEquals(1, rows.size());
        assertEquals(own, ((Number)((Map<?,?>)rows.get(0)).get("order_id")).longValue());
        List<?> employees = (List<?>)((Map<?,?>)controller.employee("month", null, null, 8L, request).data()).get("employees");
        assertEquals(1, employees.size());
        assertEquals(7L, ((Number)((Map<?,?>)employees.get(0)).get("user_id")).longValue());
        List<?> commissions = (List<?>)controller.commission(null, request).data();
        assertEquals(1, commissions.size());
        assertEquals(7L, ((Number)((Map<?,?>)commissions.get(0)).get("user_id")).longValue());
        assertEquals(new BigDecimal("100.00"), ((Map<?,?>)controller.daily(null, request).data()).get("amount"));
    }

    @Test void gramProcessingUsesBillingWeight() {
        jdbc.update("insert into processing_category(category_id,store_id,name,category_code) values(1,1,'Processing','PROC')");
        jdbc.update("insert into processing_item(item_id,store_id,category_id,name,item_code,labor_fee,pricing_unit) values(1,1,1,'Gram service','GRAM',20,'按克')");
        var controller=transactional(new com.dajin.system.processing.ProcessingController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        controller.createOrder(Map.of("processingItemId",1,"customerName","Test","customerPhone","13800000000","quantity",1,"billingWeight",10),request);
        assertEquals(new BigDecimal("200.00"),jdbc.queryForObject("select labor_fee from processing_order",BigDecimal.class));
    }

    @Test void processingUsesInventoryOnlyGoldMaterialAndConfiguredPrice() {
        jdbc.update("insert into processing_category(category_id,store_id,name,category_code) values(1,1,'Processing','PROC')");
        jdbc.update("insert into processing_item(item_id,store_id,category_id,name,item_code,labor_fee,pricing_unit) values(1,1,1,'Gold service','GOLD',20,'按件')");
        jdbc.update("insert into gold_price(store_id,price_type,price,sale_price,recycle_price,date,operator_id) values(1,'足金',612,612,578,current_date,1)");
        jdbc.update("insert into goods(goods_id,store_id,barcode,name,category_id,price_type,stock,cost_price,sale_price,status) values(10,1,'GOLD-MATERIAL-10','足金用料',1,1,151,0,0,0)");

        var controller=transactional(new com.dajin.system.processing.ProcessingController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        var order=(Map<?,?>)controller.createOrder(Map.of("processingItemId",1,"customerName","Test","customerPhone","13800000000","quantity",1,"storeGoldWeight",5,"storeGoldPrice",0),request).data();

        assertEquals(new BigDecimal("146.000"),jdbc.queryForObject("select stock from goods where goods_id=10",BigDecimal.class));
        assertEquals(new BigDecimal("612.00"),jdbc.queryForObject("select store_gold_price from processing_order where processing_order_id=?",BigDecimal.class,order.get("processing_order_id")));
        assertEquals(10L,jdbc.queryForObject("select store_gold_goods_id from processing_order where processing_order_id=?",Long.class,order.get("processing_order_id")));
    }

    @Test void processingGroupPaymentSettlesActualAmountWithoutCreatingArrears() {
        var migration = new com.dajin.system.config.SchemaCompatibilityMigration(jdbc);
        migration.run();
        assertEquals(2, jdbc.queryForObject("select count(*) from pay_channel where store_id=1 and channel_code in ('DOUYIN_GROUP','MEITUAN_GROUP')", Integer.class));
        jdbc.update("delete from pay_channel where store_id=1 and channel_code='MEITUAN_GROUP'");
        migration.run();
        assertEquals(0, jdbc.queryForObject("select count(*) from pay_channel where store_id=1 and channel_code='MEITUAN_GROUP'", Integer.class));

        jdbc.update("insert into processing_category(category_id,store_id,name,category_code) values(1,1,'Processing','PROC')");
        jdbc.update("insert into processing_item(item_id,store_id,category_id,name,item_code,labor_fee) values(1,1,1,'Service','PIECE',200)");
        var controller=transactional(new com.dajin.system.processing.ProcessingController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        var order=(Map<?,?>)controller.createOrder(Map.of("processingItemId",1,"customerName","Test","customerPhone","13800000000","quantity",1),request).data();
        long id=((Number)order.get("processing_order_id")).longValue();
        jdbc.update("update processing_order set status='COMPLETED' where processing_order_id=?", id);
        controller.pay(id,Map.of("clientRequestId","deposit-1","paymentType","DEPOSIT","payMethod","CASH","amount",50),request);
        var payment=Map.<String,Object>of("clientRequestId","group-1","paymentType","BALANCE","payMethod","DOUYIN_GROUP","amount",130,"voucherNo","DY-123");
        controller.pay(id,payment,request);
        controller.pay(id,payment,request);

        var settled=jdbc.queryForMap("select original_due_amount,promotion_discount,due_amount,paid_amount,promotion_channel,voucher_no from processing_order where processing_order_id=?",id);
        assertEquals(new BigDecimal("200.00"),settled.get("original_due_amount"));
        assertEquals(new BigDecimal("20.00"),settled.get("promotion_discount"));
        assertEquals(new BigDecimal("180.00"),settled.get("due_amount"));
        assertEquals(new BigDecimal("180.00"),settled.get("paid_amount"));
        assertEquals("DOUYIN_GROUP",settled.get("promotion_channel"));
        assertEquals("DY-123",settled.get("voucher_no"));
        assertEquals(new BigDecimal("180.00"),jdbc.queryForObject("select sum(amount) from processing_payment where processing_order_id=?",BigDecimal.class,id));
        assertEquals(new BigDecimal("180.00"),jdbc.queryForObject("select sum(amount) from finance_record where category='PROCESSING_FEE'",BigDecimal.class));

        var second=(Map<?,?>)controller.createOrder(Map.of("processingItemId",1,"customerName","Second","customerPhone","13800000001","quantity",1),request).data();
        long secondId=((Number)second.get("processing_order_id")).longValue();
        jdbc.update("update processing_order set status='COMPLETED' where processing_order_id=?",secondId);
        assertEquals(409717,assertThrows(BusinessException.class,()->controller.pay(secondId,Map.of("clientRequestId","group-2","paymentType","BALANCE","payMethod","DOUYIN_GROUP","amount",180,"voucherNo","DY-123"),request)).getCode());
        assertEquals(0,jdbc.queryForObject("select count(*) from processing_payment where processing_order_id=?",Integer.class,secondId));
    }

    @Test void cashierProcessingNegotiationSettlesAndNotifiesWithoutApproval() {
        jdbc.update("insert into sys_role(role_id,store_id,role_name,role_code) values(2,1,'Manager','MANAGER'),(3,1,'Cashier','CASHIER')");
        jdbc.update("insert into sys_user(user_id,store_id,username,password,real_name,role_id) values(2,1,'cashier','test','Cashier',3),(3,1,'manager','test','Manager',2)");
        request.setAttribute("claims", Jwts.claims(Map.of("role", "CASHIER", "storeId", 1L)).setSubject("2"));
        jdbc.update("insert into processing_category(category_id,store_id,name,category_code) values(1,1,'Processing','PROC')");
        jdbc.update("insert into processing_item(item_id,store_id,category_id,name,item_code,labor_fee) values(1,1,1,'Service','PIECE',200)");
        var controller=transactional(new com.dajin.system.processing.ProcessingController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        var order=(Map<?,?>)controller.createOrder(Map.of("processingItemId",1,"customerName","Test","customerPhone","13800000000","quantity",1),request).data();
        long id=((Number)order.get("processing_order_id")).longValue();
        jdbc.update("update processing_order set status='COMPLETED' where processing_order_id=?", id);
        Map<String,Object> payment=Map.of("clientRequestId","processing-bargain","paymentType","BALANCE","payMethod","CASH","amount",180,"settlementMode","DISCOUNT","settlementDiscountReason","顾客议价");
        controller.pay(id,payment,request);
        assertEquals(true, ((Map<?,?>)controller.pay(id,payment,request).data()).get("idempotentReplay"));

        var settled=jdbc.queryForMap("select original_due_amount,promotion_discount,due_amount,paid_amount,promotion_channel from processing_order where processing_order_id=?",id);
        assertEquals(new BigDecimal("200.00"),settled.get("original_due_amount"));
        assertEquals(new BigDecimal("20.00"),settled.get("promotion_discount"));
        assertEquals(new BigDecimal("180.00"),settled.get("due_amount"));
        assertEquals(new BigDecimal("180.00"),settled.get("paid_amount"));
        assertNull(settled.get("promotion_channel"));
        assertEquals(new BigDecimal("180.00"),jdbc.queryForObject("select sum(amount) from finance_record where category='PROCESSING_FEE'",BigDecimal.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval where biz_id=?",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("select count(*) from operation_log where module='NOTIFICATION' and action='BARGAIN'",Integer.class));
    }

    @Test void repeatedProcessingGoldReturnsHaveSeparateLedgerEntries() {
        jdbc.update("insert into processing_category(category_id,store_id,name,category_code) values(1,1,'Processing','PROC')");
        jdbc.update("insert into processing_item(item_id,store_id,category_id,name,item_code,labor_fee) values(1,1,1,'Service','PIECE',20)");
        jdbc.update("update goods set name='足金用料',stock=100 where goods_id=1");
        var controller=transactional(new com.dajin.system.processing.ProcessingController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        var order=(Map<?,?>)controller.createOrder(Map.of("processingItemId",1,"customerName","Test","customerPhone","13800000000","quantity",1),request).data();
        long id=((Number)order.get("processing_order_id")).longValue();
        controller.changeStatus(id,Map.of("status","PROCESSING"),request);
        for(int weight:new int[]{10,8,6}) controller.registerStoreGold(id,Map.of("weight",weight,"price",100),request);
        assertEquals(94,inventory());
        assertEquals(new BigDecimal("4.000"),jdbc.queryForObject("select sum(qty) from stock_in",BigDecimal.class));
    }

    @Test void trackedPieceCancellationAndConcurrentPaymentAreIdempotent() throws Exception {
        jdbc.update("insert into goods_piece(store_id,goods_id,piece_no) values(1,1,'TRACKED')");
        long cancelled=create("tracked-cancel");
        orders.cancel(cancelled,Map.of(),request); orders.cancel(cancelled,Map.of(),request);
        assertEquals(1,inventory());
        assertEquals(1,jdbc.queryForObject("select status from goods_piece",Integer.class));
        long id=create("tracked-pay");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);
            List<Future<?>> results=new ArrayList<>();
            for(int n=0;n<2;n++) results.add(pool.submit(() -> { start.await(); pay(id,"same-payment"); return null; }));
            start.countDown();
            for(Future<?> result:results) result.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(0,inventory());
        assertEquals(new BigDecimal("900.00"),balance());
        assertEquals(0,jdbc.queryForObject("select status from goods_piece",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from stock_out",Integer.class));
    }

    @Test void followupCanBeScheduledRepeatedlyWithoutDuplicateTasksOnRetry() {
        long id=create("followup"); pay(id,"followup-pay");
        var controller=transactional(new com.dajin.system.visit.VisitController(db,ws));
        for(int n=0;n<3;n++) {
            long task=jdbc.queryForObject("select task_id from visit_task where status=1",Long.class);
            Map<String,Object> body=Map.of("taskId",task,"record","called","nextFollowUp","2026-12-01 12:00:00");
            controller.record(body,request); controller.record(body,request);
        }
        assertEquals(4,jdbc.queryForObject("select count(*) from visit_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from visit_task where status=1",Integer.class));
    }

    @Test void rechargeIsIdempotentAndSeparateFromConsumption() {
        var controller=transactional(new com.dajin.system.member.MemberController(db,ws));
        var body=Map.<String,Object>of("amount",50,"type","RECHARGE","payMethod","CASH","clientRequestId","recharge-1");
        controller.balance(1,body,request); controller.balance(1,body,request);
        assertEquals(new BigDecimal("1050.00"),balance());
        assertEquals(0,jdbc.queryForObject("select count(*) from member_consume",Integer.class));
        assertEquals(new BigDecimal("50.00"),jdbc.queryForObject("select sum(amount) from finance_record where category='MEMBER_RECHARGE' and pay_method='CASH' and shift_no='SHIFT-TEST'",BigDecimal.class));
        assertEquals(1,((List<?>)controller.balanceRecords(1,request).data()).size());
        jdbc.update("insert into member_consume(store_id,member_id,amount,consume_time) values(1,1,20,now())");
        assertEquals(2,((List<?>)controller.balanceRecords(1,request).data()).size());
        assertEquals(0,((List<?>)controller.consume(1,request).data()).size());
    }

    @Test void standaloneTradeInCannotCompleteWithoutActualGoodsAndPayment() {
        jdbc.update("insert into gold_price(store_id,price_type,price,date) values(1,'回收金价',100,curdate())");
        var controller=new com.dajin.system.tradein.TradeInController();
        assertThrows(BusinessException.class, () -> controller.create(Map.of("oldMaterials",List.of(Map.of("weight",1,"purity",1)),"newValue",200),request));
        assertEquals(0,jdbc.queryForObject("select count(*) from trade_in",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from old_material",Integer.class));
    }

    @Test void refundRecordsReturnedOldMaterial() {
        jdbc.update("insert into gold_price(store_id,price_type,price,date) values(1,'回收金价',100,curdate())");
        var q=sale("material-refund");
        var item=new OrderController.OldMaterialItem("足金999",BigDecimal.ONE,BigDecimal.ONE,"回收金价",new BigDecimal("100"),"");
        var body=new OrderController.Req(q.memberId(),q.discount(),BigDecimal.ZERO,q.laborFee(),null,null,null,q.salesId(),"",q.items(),List.of(item),q.clientRequestId(),null,false);
        long id=((Number)((Map<?,?>)orders.create(body,request).data()).get("orderId")).longValue();
        payments.pay(Map.of("orderId",id,"clientRequestId","material-pay","amount",0),request);
        refund(id);
        assertEquals(new BigDecimal("1.000"),jdbc.queryForObject("select sum(qty) from stock_out where type='OLD_MATERIAL_OUT'",BigDecimal.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from old_material where status=1",Integer.class));
    }

    @Test void cashierCanFindHandedOverOrderInProcessingListAfterStartingIt() {
        jdbc.update("insert into processing_category(category_id,store_id,name,category_code) values(1,1,'Processing','PROC')");
        jdbc.update("insert into processing_item(item_id,store_id,category_id,name,item_code,labor_fee) values(1,1,1,'Service','SERVICE',20)");
        var controller = transactional(new com.dajin.system.processing.ProcessingController(db,ws,new ShiftService(db),new OldMaterialLedgerService(db)));
        var order = (Map<?,?>) controller.createOrder(Map.of("processingItemId",1,"customerName","Test","customerPhone","13800000000","quantity",1),request).data();
        long id = ((Number) order.get("processing_order_id")).longValue();
        controller.handover(id, request);
        request.setAttribute("claims", Jwts.claims(Map.of("role", "CASHIER", "storeId", 1L)).setSubject("2"));
        assertEquals(1, ((List<?>) controller.handovers(request).data()).size());
        controller.changeStatus(id, Map.of("status", "PROCESSING"), request);
        assertTrue(((List<?>) controller.handovers(request).data()).isEmpty());
        var active = (List<?>) controller.orders(null, "PROCESSING", null, null, null, null, request).data();
        assertEquals(1, active.size());
        assertEquals(id, ((Number) ((Map<?,?>) active.get(0)).get("processing_order_id")).longValue());
        assertEquals("PROCESSING", ((Map<?,?>) controller.detail(id, request).data()).get("status"));
    }

    @Test void upgradeRepairsInterruptedFollowupIndexWithoutRejectingExistingTasks() {
        jdbc.update("insert into visit_task(store_id,member_id,sales_id,order_id,visit_type,followup_no) values(1,1,1,900,'PURCHASE_3D',0),(1,1,1,900,'PURCHASE_3D',1)");
        jdbc.execute("alter table visit_task drop index uk_visit_order_type");
        var migration = new com.dajin.system.config.SchemaCompatibilityMigration(jdbc);
        try {
            migration.run();
            migration.run();
            assertEquals(2, jdbc.queryForObject("select count(*) from visit_task where order_id=900", Integer.class));
            assertEquals("store_id,order_id,visit_type,followup_no", jdbc.queryForObject("select group_concat(column_name order by seq_in_index) from information_schema.statistics where table_schema=database() and table_name='visit_task' and index_name='uk_visit_order_type'", String.class));
        } finally {
            Integer exists = jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='visit_task' and index_name='uk_visit_order_type'", Integer.class);
            if (exists == 0) jdbc.execute("alter table visit_task add unique key uk_visit_order_type(store_id,order_id,visit_type,followup_no)");
        }
    }

    @Test void legacyAuditSchemaUpgradesWithoutChangingStockOrMemberBalance() {
        long id = create("legacy-upgrade");
        jdbc.execute("drop table member_balance_record");
        jdbc.execute("alter table sales_order drop index idx_order_paid, drop column paid_time, drop column commission_rate_snapshot, drop column handover");
        jdbc.execute("alter table processing_order drop column billing_weight, drop column handover, drop column handover_time, drop column store_gold_weight, drop column loss_note");
        jdbc.execute("alter table visit_task drop index uk_visit_order_type, drop column followup_no, add unique key uk_visit_order_type(store_id,order_id,visit_type)");
        var migration = new com.dajin.system.config.SchemaCompatibilityMigration(jdbc);
        migration.run();
        migration.run();
        assertEquals(new BigDecimal("1000.00"), balance());
        assertEquals(1, inventory());
        assertEquals(0, jdbc.queryForObject("select count(*) from member_balance_record", Integer.class));
        assertEquals(5, jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='processing_order' and column_name in ('billing_weight','handover','handover_time','store_gold_weight','loss_note')", Integer.class));
        assertThrows(BusinessException.class, () -> create("legacy-still-reserved"));
        orders.cancel(id, Map.of(), request);
        assertDoesNotThrow(() -> create("legacy-released"));
    }

    @Test void actualBackupCreatesReadableDatabaseDump(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path executable=Path.of("../.build-cache/mysql-test-runtime/mysql-8.4.0-winx64/bin/mysqldump.exe").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(executable));
        var env=new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.datasource.url",source.getJdbcUrl())
                .withProperty("spring.datasource.username","root")
                .withProperty("BACKUP_DIRECTORY",directory.toString())
                .withProperty("BACKUP_MYSQLDUMP",executable.toString());
        var service=new com.dajin.system.admin.BackupService(env);
        var result=service.create();
        assertEquals("COMPLETED",result.get("status"));
        assertEquals(1,service.list().size());
        try(var input=new java.util.zip.GZIPInputStream(Files.newInputStream(directory.resolve(result.get("fileName").toString())))) {
            String sql=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE `sales_order`"));
            assertTrue(sql.contains("Test store"));
            assertTrue(sql.contains("Dump completed"));
        }
    }

    @Test void excessRefundRequiresRecoveryConfirmationAndReversesPayoutOnce() {
        jdbc.update("insert into gold_price(store_id,price_type,price,date) values(1,'回收金价',100,curdate())");
        var q=sale("excess-refund");
        var old=new OrderController.OldMaterialItem("足金999",new BigDecimal("2"),BigDecimal.ONE,"回收金价",new BigDecimal("100"),"");
        var body=new OrderController.Req(q.memberId(),q.discount(),BigDecimal.ZERO,q.laborFee(),null,null,"CASH",q.salesId(),"",q.items(),List.of(old),q.clientRequestId(),null,false);
        long id=((Number)((Map<?,?>)orders.create(body,request).data()).get("orderId")).longValue();
        payments.pay(Map.of("orderId",id,"clientRequestId","excess-pay","amount",0),request);
        assertThrows(BusinessException.class,()->refund(id));
        long approval=jdbc.queryForObject("select max(approval_id) from approval where type='REFUND'",Long.class);
        assertEquals(1,jdbc.queryForObject("select status from sales_order where order_id=?",Integer.class,id));
        approvals.approve(approval,Map.of("oldMaterialExcessRecovered",true),request);
        assertThrows(BusinessException.class,()->approvals.approve(approval,Map.of("oldMaterialExcessRecovered",true),request));
        assertEquals(new BigDecimal("100.00"),jdbc.queryForObject("select sum(amount) from finance_record where category='RECYCLE_REFUND' and pay_method='CASH'",BigDecimal.class));
        assertEquals(1,inventory());
    }
}
