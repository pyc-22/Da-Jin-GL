package com.dajin.system.processing;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingSalesScopeTests {
    @Test
    void listIncludesOrdersCreatedByOrAssignedToCurrentSales() {
        DbSupport db = mock(DbSupport.class);
        HttpServletRequest request = salesRequest(27L);
        when(db.store(request)).thenReturn(1L);
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        ProcessingController controller = new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class));

        controller.orders(null, null, null, null, null, null, null, request);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("(o.created_by=:uid or o.sales_id=:uid)"));
    }

    @Test
    void assignedSalesCanReadAndNotifyOrderCreatedByAnotherSales() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        HttpServletRequest request = salesRequest(27L);
        Map<String, Object> order = new HashMap<>();
        order.put("processing_order_id", 8L);
        order.put("store_id", 1L);
        order.put("order_no", "JG-8");
        order.put("status", "COMPLETED");
        order.put("created_by", 99L);
        order.put("sales_id", 27L);
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.one(anyString(), anyMap())).thenReturn(order);
        when(db.list(anyString(), anyMap())).thenReturn(List.of());
        ProcessingController controller = new ProcessingController(db, ws, mock(ShiftService.class), mock(OldMaterialLedgerService.class));

        controller.detail(8L, request);
        controller.notifyPickup(8L, request);

        verify(jdbc).update(contains("'PROCESSING_READY'"), any(SqlParameterSource.class));
        var params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(contains("'PROCESSING_READY'"), params.capture());
        assertTrue(String.valueOf(params.getValue().getValue("uid")).equals("27"));
    }

    private HttpServletRequest salesRequest(long userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(request.getAttribute("claims")).thenReturn(claims);
        when(claims.get("role")).thenReturn("SALES");
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        return request;
    }
}
