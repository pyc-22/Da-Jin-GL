package com.dajin.system.goods;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.mockito.ArgumentCaptor;

class GoodsControllerSyncTests {
    @Test
    void priceEditCannotRestoreStockFromAnOldForm() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(jdbc.update(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class))).thenReturn(1);
        GoodsController controller = new GoodsController(db, mock(SyncWebSocketHandler.class));
        controller.update(99L, new GoodsController.GoodsReq("TEST-001", "Sold item", null, null,
                BigDecimal.TEN, new BigDecimal("268"), 2, null, BigDecimal.ONE, "[]", null), request);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
        org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().matches("(?s).*\\bstock\\s*=.*"));
    }

    @Test
    void broadcastsGoodsUpdateAfterPriceEdit() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        when(jdbc.update(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class))).thenReturn(1);
        GoodsController controller = new GoodsController(db, ws);
        GoodsController.GoodsReq body = new GoodsController.GoodsReq(
                "TEST-001", "测试商品", null, new BigDecimal("1.000"),
                new BigDecimal("100.00"), new BigDecimal("268.00"),
                2, null, null, "[]", null);

        controller.update(99L, body, request);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(ws).broadcast(eq("GOODS_UPDATED"), event.capture());
        Map<?, ?> payload = assertInstanceOf(Map.class, event.getValue());
        assertEquals(1L, payload.get("storeId"));
        assertEquals(99L, payload.get("goodsId"));
        assertEquals("UPDATE", payload.get("action"));
    }

    @Test
    void decimalStockSurvivesJsonBinding() throws Exception {
        // 按克商品的库存单位是克，必须是小数：stock 声明为 Integer 时 Jackson 会静默把 10.5 截断成 10。
        GoodsController.GoodsReq body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"barcode\":\"T-1\",\"name\":\"按克商品\",\"categoryId\":54,\"weight\":10.5,"
                        + "\"costPrice\":860,\"salePrice\":0,\"priceType\":1,\"goldType\":\"足金\","
                        + "\"stock\":10.5,\"images\":\"[]\",\"certificateNo\":\"\"}",
                GoodsController.GoodsReq.class);
        assertEquals(0, new BigDecimal("10.5").compareTo(body.stock()));
        assertEquals(0, new BigDecimal("10.5").compareTo(body.weight()));
    }
}
