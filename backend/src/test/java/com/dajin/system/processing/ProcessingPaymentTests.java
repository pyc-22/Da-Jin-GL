package com.dajin.system.processing;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.math.BigDecimal;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingPaymentTests {
    private record Fixture(DbSupport db, NamedParameterJdbcTemplate jdbc, HttpServletRequest request,
                           ProcessingController controller, Map<String, Object> order) { }

    private Fixture fixture(String status, int due, int paid) {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = new HashMap<>(Map.of("processing_order_id", 7L, "order_no", "JG-7",
                "store_id", 1L, "status", status, "due_amount", due, "paid_amount", paid));
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.list(contains("from processing_order"), anyMap())).thenReturn(List.of(order));
        when(db.list(contains("from processing_payment"), anyMap())).thenReturn(List.of());
        when(db.one(contains("from processing_order"), anyMap())).thenReturn(order);
        when(jdbc.queryForObject(contains("from pay_channel"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("promotion_channel"), anyMap(), eq(Integer.class))).thenReturn(0);
        return new Fixture(db, jdbc, request, new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class)), order);
    }

    @Test
    void groupBalanceRecordsActualPaymentAndDiscountWithExistingDeposit() {
        Fixture f = fixture("COMPLETED", 200, 50);
        f.controller.pay(7L, Map.of("clientRequestId", "group-7", "paymentType", "BALANCE",
                "payMethod", "DOUYIN_GROUP", "amount", 130, "voucherNo", "DY-123"), f.request);

        ArgumentCaptor<SqlParameterSource> orderParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(f.jdbc).update(contains("original_due_amount=due_amount"), orderParams.capture());
        assertEquals(new BigDecimal("20.00"), orderParams.getValue().getValue("discount"));
        assertEquals("DY-123", orderParams.getValue().getValue("voucher"));
        ArgumentCaptor<SqlParameterSource> financeParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(f.jdbc).update(contains("insert into finance_record"), financeParams.capture());
        assertEquals(new BigDecimal("130.00"), financeParams.getValue().getValue("amount"));
    }

    @Test
    void ordinaryBalanceStillRequiresFullOutstandingAmount() {
        Fixture f = fixture("COMPLETED", 200, 0);
        BusinessException error = assertThrows(BusinessException.class, () -> f.controller.pay(7L, Map.of(
                "clientRequestId", "cash-7", "paymentType", "BALANCE", "payMethod", "CASH", "amount", 180), f.request));
        assertEquals(400717, error.getCode());
        verify(f.jdbc, never()).update(contains("insert into finance_record"), any(SqlParameterSource.class));
    }

    @Test
    void groupPaymentRequiresCompletedBalanceAndVoucher() {
        Fixture f = fixture("PROCESSING", 200, 0);
        BusinessException statusError = assertThrows(BusinessException.class, () -> f.controller.pay(7L, Map.of(
                "clientRequestId", "group-status", "paymentType", "BALANCE", "payMethod", "MEITUAN_GROUP",
                "amount", 180, "voucherNo", "MT-123"), f.request));
        assertEquals(400724, statusError.getCode());
        f.order.put("status", "COMPLETED");
        BusinessException voucherError = assertThrows(BusinessException.class, () -> f.controller.pay(7L, Map.of(
                "clientRequestId", "group-voucher", "paymentType", "BALANCE", "payMethod", "MEITUAN_GROUP",
                "amount", 180), f.request));
        assertEquals(400700, voucherError.getCode());
        BusinessException depositError = assertThrows(BusinessException.class, () -> f.controller.pay(7L, Map.of(
                "clientRequestId", "group-deposit", "paymentType", "DEPOSIT", "payMethod", "MEITUAN_GROUP",
                "amount", 180, "voucherNo", "MT-123"), f.request));
        assertEquals(400724, depositError.getCode());
    }

    @Test
    void duplicateVoucherCannotSettleAnotherOrder() {
        Fixture f = fixture("COMPLETED", 200, 0);
        when(f.jdbc.queryForObject(contains("promotion_channel"), anyMap(), eq(Integer.class))).thenReturn(1);
        BusinessException error = assertThrows(BusinessException.class, () -> f.controller.pay(7L, Map.of(
                "clientRequestId", "group-duplicate", "paymentType", "BALANCE", "payMethod", "DOUYIN_GROUP",
                "amount", 180, "voucherNo", "DY-123"), f.request));
        assertEquals(409717, error.getCode());
        verify(f.jdbc, never()).update(contains("insert into finance_record"), any(SqlParameterSource.class));
    }

    @Test
    void groupPaymentRejectsOverCollectionAndSecondPromotion() {
        Fixture f = fixture("COMPLETED", 200, 50);
        Map<String, Object> body = Map.of("clientRequestId", "group-extra", "paymentType", "BALANCE",
                "payMethod", "DOUYIN_GROUP", "amount", 151, "voucherNo", "DY-124");
        assertEquals(409707, assertThrows(BusinessException.class,
                () -> f.controller.pay(7L, body, f.request)).getCode());
        f.order.put("promotion_channel", "MEITUAN_GROUP");
        assertEquals(400724, assertThrows(BusinessException.class,
                () -> f.controller.pay(7L, body, f.request)).getCode());
    }

    @Test
    void ordinaryPaymentRejectsVoucherMetadata() {
        Fixture f = fixture("COMPLETED", 200, 0);
        assertEquals(400724, assertThrows(BusinessException.class, () -> f.controller.pay(7L, Map.of(
                "clientRequestId", "cash-voucher", "paymentType", "BALANCE", "payMethod", "CASH",
                "amount", 200, "voucherNo", "DY-123"), f.request)).getCode());
    }

    @Test
    void replayDoesNotApplyDiscountTwice() {
        Fixture f = fixture("COMPLETED", 180, 180);
        when(f.db.list(contains("from processing_payment"), anyMap()))
                .thenReturn(List.of(Map.of("payment_id", 1L, "processing_order_id", 7L)));
        ApiResponse<?> response = f.controller.pay(7L, Map.of("clientRequestId", "group-7"), f.request);
        assertEquals(true, ((Map<?, ?>) response.data()).get("idempotentReplay"));
        verify(f.jdbc, never()).update(contains("update processing_order set original_due_amount"), any(SqlParameterSource.class));
    }

    @Test
    void storedValuePaymentRejectsInsufficientMemberBalance() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        Map<String,Object> order = new HashMap<>(Map.of("processing_order_id", 7L, "order_no", "JG-7",
                "store_id", 1L, "status", "PROCESSING", "member_id", 2L, "due_amount", 100, "paid_amount", 0));
        when(db.list(contains("from processing_order"), anyMap())).thenReturn(List.of(order));
        when(db.list(contains("from processing_order"), any(SqlParameterSource.class))).thenReturn(List.of(order));
        when(db.one(anyString(), anyMap())).thenReturn(order);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(order);
        when(jdbc.queryForObject(contains("from pay_channel"), anyMap(), eq(Integer.class))).thenReturn(1);
        ProcessingController controller = new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class));

        BusinessException error = assertThrows(BusinessException.class, () -> controller.pay(7L, Map.of(
                "clientRequestId", "balance-7", "paymentType", "DEPOSIT", "payMethod", "BALANCE", "amount", 100), request));
        assertEquals(409106, error.getCode());
        verify(jdbc, never()).update(contains("insert into processing_payment"), any(SqlParameterSource.class));
    }
}
