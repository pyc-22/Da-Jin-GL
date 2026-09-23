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
                BigDecimal.TEN, new BigDecimal("268"), 2, null, 1, "[]", null), request);
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
}
