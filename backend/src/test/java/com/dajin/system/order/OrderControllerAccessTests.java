package com.dajin.system.order;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderControllerAccessTests {
    @Test
    void salesCannotReadAnotherOperatorsOrderDetail() {
        DbSupport db = mock(DbSupport.class);
        when(db.store(any())).thenReturn(1L);
        Map<String, Object> order = new HashMap<>(Map.of("order_id", 8L, "sales_id", 22L, "cashier_id", 33L));
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(order);
        var controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());

        assertThrows(com.dajin.system.common.BusinessException.class, () -> controller.detail(8L, request("SALES", 44L)));
        verify(db, never()).list(contains("sales_order_item"), any(SqlParameterSource.class));
    }

    @Test
    void salesListQueryScopesToAssignedOrCashierOrders() {
        DbSupport db = mock(DbSupport.class);
        when(db.store(any())).thenReturn(1L);
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        var controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());

        controller.list(1, 20, null, null, request("SALES", 44L));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("o.sales_id=:uid or o.cashier_id=:uid"));
    }

    private MockHttpServletRequest request(String role, long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("storeId", 1L);
        Claims claims = mock(Claims.class);
        when(claims.get("role")).thenReturn(role);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        request.setAttribute("claims", claims);
        return request;
    }
}
