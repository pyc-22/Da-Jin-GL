package com.dajin.system.order;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.common.ApiResponse;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderControllerStockTests {
    @Test
    void rejectsSoldOutGoodsBeforeCreatingAnotherPendingOrder() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.one("select stock,status from goods where goods_id=:g and store_id=:s", Map.of("g", 2064L, "s", 1L)))
                .thenReturn(Map.of("stock", new BigDecimal("0.000"), "status", 1));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("sales_order_item"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item item = new OrderController.Item(2064L, "999金测试戒指", new BigDecimal("5.000"),
                new BigDecimal("300.00"), BigDecimal.ZERO, 1, new BigDecimal("1500.00"), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.validateStockAvailability(item, request));

        assertEquals(409103, error.getCode());
    }

    @Test
    void allowsGoodsWhenRequestedQuantityIsAvailable() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.one("select stock,status from goods where goods_id=:g and store_id=:s", Map.of("g", 2064L, "s", 1L)))
                .thenReturn(Map.of("stock", new BigDecimal("1.000"), "status", 1));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("sales_order_item"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item item = new OrderController.Item(2064L, "999金测试戒指", new BigDecimal("5.000"),
                new BigDecimal("300.00"), BigDecimal.ZERO, 1, new BigDecimal("1500.00"), null);

        assertDoesNotThrow(() -> controller.validateStockAvailability(item, request));
    }

    @Test
    void rejectsGoodsAlreadyReservedByPendingOrder() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.one("select stock,status from goods where goods_id=:g and store_id=:s", Map.of("g", 2064L, "s", 1L)))
                .thenReturn(Map.of("stock", new BigDecimal("1.000"), "status", 1));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("sales_order_item"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(BigDecimal.class))).thenReturn(new BigDecimal("1.000"));
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item item = new OrderController.Item(2064L, "999金测试戒指", new BigDecimal("5.000"),
                new BigDecimal("300.00"), BigDecimal.ZERO, 1, new BigDecimal("1500.00"), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.validateStockAvailability(item, request));

        assertEquals(409103, error.getCode());
    }

    @Test
    void aggregatesDuplicateGoodsLinesBeforeCheckingStock() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.one("select stock,status from goods where goods_id=:g and store_id=:s for update", Map.of("g", 2064L, "s", 1L)))
                .thenReturn(Map.of("stock", new BigDecimal("1.000"), "status", 1));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("sales_order_item"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item line = new OrderController.Item(2064L, "999金测试戒指", new BigDecimal("5.000"),
                new BigDecimal("300.00"), BigDecimal.ZERO, 1, new BigDecimal("1500.00"), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.validateStockAvailability(List.of(line, line), request));

        assertEquals(409103, error.getCode());
    }

    @Test
    void cancellingPendingOrderReleasesPieceReservation() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.list(org.mockito.ArgumentMatchers.contains("from sales_order where order_id=:id"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(Map.of(
                "order_id", 88L, "order_no", "XS-88", "status", 0)));
        OrderController controller = new OrderController(db, ws, new ObjectMapper());

        controller.cancel(88L, Map.of("reason", "客户取消"), request);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("goods_piece set status=1,sales_order_id=null"),
                org.mockito.ArgumentMatchers.anyMap());
        verify(ws).broadcast(org.mockito.ArgumentMatchers.eq("STOCK_UPDATED"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void cancellingAlreadyCancelledOrderIsIdempotentAndDoesNotReleaseTwice() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(db.list(org.mockito.ArgumentMatchers.contains("from sales_order where order_id=:id"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(Map.of(
                "order_id", 89L, "order_no", "XS-89", "status", 4)));
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());

        ApiResponse<?> result = controller.cancel(89L, Map.of("reason", "重复点击"), request);

        assertEquals(200, result.code());
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("goods_piece set status=1"),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void rejectsOrderWhenTrackedPiecesCannotBeFullyReserved() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("count(*) from goods_piece"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(1);
        when(jdbc.update(org.mockito.ArgumentMatchers.contains("update goods_piece set status=2"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class))).thenReturn(0);
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item item = new OrderController.Item(2064L, "999金测试戒指", new BigDecimal("5.000"),
                new BigDecimal("300.00"), BigDecimal.ZERO, 1, new BigDecimal("1500.00"), List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.reservePieces(88L, List.of(item), request));

        assertEquals(409115, error.getCode());
    }

    @Test
    void allowsAggregateGoodsWithoutPieceRecords() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("count(*) from goods_piece"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(0);
        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item item = new OrderController.Item(2064L, "按总量管理商品", null,
                new BigDecimal("300.00"), BigDecimal.ZERO, 1, new BigDecimal("300.00"), List.of());

        assertDoesNotThrow(() -> controller.reservePieces(88L, List.of(item), request));
    }
}
