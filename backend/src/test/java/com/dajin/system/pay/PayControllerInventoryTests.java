package com.dajin.system.pay;

import com.dajin.system.common.DbSupport;
import com.dajin.system.common.ApiResponse;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayControllerInventoryTests {
    @Test
    void cashierNegotiatedSaleSettlesAndNotifiesManagers() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(request.getAttribute("claims")).thenReturn(Jwts.claims(Map.of("role", "CASHIER")).setSubject("2"));
        when(db.one(contains("select * from sales_order"), anyMap())).thenReturn(Map.of(
                "order_id", 77L, "store_id", 1L, "status", 0, "order_no", "XS-77",
                "total_amount", new BigDecimal("140.00"), "discount", BigDecimal.ONE,
                "labor_fee", BigDecimal.ZERO, "old_material_deduct", BigDecimal.ZERO,
                "pay_amount", BigDecimal.ZERO, "settlement_discount", BigDecimal.ZERO));
        when(db.list(contains("join sys_role"), anyMap())).thenReturn(List.of(Map.of("user_id", 1L)));
        when(jdbc.queryForObject(contains("pay_channel"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(jdbc.update(contains("update sales_order"), any(MapSqlParameterSource.class))).thenReturn(1);
        PayController controller = new PayController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

        ApiResponse<?> response = controller.pay(Map.of(
                "orderId", 77L, "clientRequestId", "bargain-77", "amount", 120,
                "settlementMode", "DISCOUNT", "settlementDiscountReason", "顾客议价",
                "payMethod", "CASH"), request);

        Map<?, ?> result = (Map<?, ?>) response.data();
        assertEquals(1, result.get("status"));
        assertEquals(new BigDecimal("120.00"), result.get("actualPaid"));
        assertEquals(new BigDecimal("20.00"), result.get("settlementDiscount"));
        var parameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("'NOTIFICATION'"), parameters.capture());
        assertEquals(1L, parameters.getValue().getValue("uid"));
        assertTrue(String.valueOf(parameters.getValue().getValue("content")).contains("XS-77"));
    }

    @Test
    void zeroPayableOrderDoesNotCreateZeroAmountPaymentLine() {
        PayController controller = new PayController(mock(DbSupport.class), mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

        assertTrue(controller.parseLines(Map.of(), BigDecimal.ZERO).isEmpty());
    }

    @Test
    void recordsExcessOldMaterialAsRecycleExpenseAgainstSaleBill() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        PayController controller = new PayController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

        controller.recordOldMaterialExcess(Map.of(
                "old_material_excess", new BigDecimal("31.70"),
                "order_no", "XS20260916001"
        ), "CASH", "SHIFT-001", request);

        var parameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("'EXPENSE','RECYCLE'"), parameters.capture());
        assertEquals(new BigDecimal("31.70"), parameters.getValue().getValue("amount"));
        assertEquals("CASH", parameters.getValue().getValue("pay"));
        assertEquals("XS20260916001", parameters.getValue().getValue("no"));
    }

    @Test
    void bindsWholePieceQuantityAsIntegerForMysqlLimit() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.list(contains("select goods_id,qty,piece_nos"), anyMap())).thenReturn(List.of(Map.of(
                "goods_id", 2070L,
                "qty", new BigDecimal("1.000"),
                "piece_nos", "[]"
        )));
        when(jdbc.update(contains("update goods set stock=stock-"), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.update(contains("status=2 and sales_order_id=:o"), any(MapSqlParameterSource.class))).thenReturn(1);

        PayController controller = new PayController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());
        controller.consumeOrderInventory(16660L, request);

        var parameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("limit :pieceLimit"), parameters.capture());
        Object pieceLimit = parameters.getValue().getValue("pieceLimit");
        assertInstanceOf(Integer.class, pieceLimit);
        assertEquals(1, pieceLimit);
    }

    @Test
    void paymentRetryWithSameRequestIdReplaysAfterTheOrderLock() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.one(contains("select * from sales_order"), anyMap())).thenReturn(Map.of(
                "order_id", 77L, "status", 1, "order_no", "XS-77",
                "total_amount", new BigDecimal("100.00"), "discount", BigDecimal.ONE,
                "labor_fee", BigDecimal.ZERO, "old_material_deduct", BigDecimal.ZERO,
                "pay_amount", new BigDecimal("100.00"), "settlement_discount", BigDecimal.ZERO));
        when(db.list(contains("from operation_log"), anyMap())).thenReturn(List.of(Map.of("log_id", 12L)));
        PayController controller = new PayController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

        ApiResponse<?> result = controller.pay(Map.of(
                "orderId", 77L, "clientRequestId", "pay-retry-77"), request);

        assertEquals(200, result.code());
        assertTrue((Boolean) ((Map<?, ?>) result.data()).get("idempotentReplay"));
        verify(jdbc, never()).update(contains("update goods set stock=stock-"), any(MapSqlParameterSource.class));
    }

    @Test
    void rejectsPaymentWhenTrackedPieceReservationCannotCoverQuantity() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.list(contains("select goods_id,qty,piece_nos"), anyMap())).thenReturn(List.of(Map.of(
                "goods_id", 2071L, "qty", new BigDecimal("1.000"), "piece_nos", "[]")));
        when(jdbc.update(contains("update goods set stock=stock-"), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.update(contains("status=2 and sales_order_id=:o"), any(MapSqlParameterSource.class))).thenReturn(0);
        PayController controller = new PayController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

        org.junit.jupiter.api.Assertions.assertThrows(com.dajin.system.common.BusinessException.class,
                () -> controller.consumeOrderInventory(16661L, request));
    }
}
