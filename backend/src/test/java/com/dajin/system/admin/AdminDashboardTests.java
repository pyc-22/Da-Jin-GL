package com.dajin.system.admin;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminDashboardTests {
    @Test
    void dashboardUsesShanghaiDayAndExcludesDeletedMembersAndWithdrawnProcessingOrders() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(any())).thenReturn(1L);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(
                new HashMap<>(Map.of("revenue", BigDecimal.ZERO, "sales_order_count", 0, "sales_weight", BigDecimal.ZERO)));
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class))).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(2);
            return type == Integer.class ? 0 : BigDecimal.ZERO;
        });

        MockHttpServletRequest request = managerRequest(Set.of("report:store-performance", "report:processing"));
        new AdminController(db, mock(SyncWebSocketHandler.class), mock(BackupService.class)).dashboard(request);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), any(SqlParameterSource.class));
        String all = String.join("\n", sql.getAllValues());
        assertTrue(all.contains("m.deleted=0"), all);
        assertTrue(all.contains("status<>'WITHDRAWN'"), all);
        assertTrue(all.contains("o.create_time>=:fromStart"), all);
        assertTrue(all.contains("o.create_time<:trendEnd"), all);
        assertTrue(all.contains("processing_order"), all);
        assertTrue(all.contains("status='PICKED_UP'"), all);
        var params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(db, atLeastOnce()).one(anyString(), params.capture());
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")), params.getValue().getValue("today"));
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString(), params.getValue().getValue("trendEnd"));
    }

    @Test
    void managerWithoutPerformancePermissionGetsOnlyPermittedKpis() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(any())).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class))).thenReturn(0);
        MockHttpServletRequest request = managerRequest(Set.of("dashboard:view"));

        Map<?, ?> data = (Map<?, ?>) new AdminController(db, mock(SyncWebSocketHandler.class), mock(BackupService.class))
                .dashboard(request).data();

        assertFalse(data.containsKey("ranking"));
        assertFalse(data.containsKey("revenue"));
        verify(db, never()).one(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void dashboardReturnsTheSameTodaySalesAndProcessingRowsUsedByItsOrderCount() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(any())).thenReturn(1L);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(
                new HashMap<>(Map.of("revenue", BigDecimal.ZERO, "sales_order_count", 0, "sales_weight", BigDecimal.ZERO)));
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        when(db.list(contains("today_orders"), any(SqlParameterSource.class))).thenReturn(List.of(Map.of(
                "order_type", "PROCESSING", "order_no", "JG-TODAY-1", "customer_name", "李女士",
                "status", "PENDING", "create_time", "2026-10-11 10:39", "labor_fee", BigDecimal.ZERO,
                "due_amount", BigDecimal.ZERO, "item_name", "银手镯加工")));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Class<?> type = invocation.getArgument(2);
            if (sql.contains("count(*) from processing_order")) return 1;
            return type == Integer.class ? 0 : BigDecimal.ZERO;
        });

        Map<?, ?> data = (Map<?, ?>) new AdminController(db, mock(SyncWebSocketHandler.class), mock(BackupService.class))
                .dashboard(managerRequest(Set.of("report:store-performance", "report:processing"))).data();

        assertEquals(1, new BigDecimal(String.valueOf(data.get("orderCount"))).intValueExact());
        assertEquals(0, new BigDecimal(String.valueOf(data.get("salesOrderCount"))).intValueExact());
        assertEquals(1, new BigDecimal(String.valueOf(data.get("processingOrderCount"))).intValueExact());
        List<?> details = (List<?>) data.get("todayOrderDetails");
        assertEquals(1, details.size());
        assertEquals("PROCESSING", ((Map<?, ?>) details.get(0)).get("order_type"));
        assertEquals("JG-TODAY-1", ((Map<?, ?>) details.get(0)).get("order_no"));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), any(SqlParameterSource.class));
        String detailsSql = sql.getAllValues().stream().filter(value -> value.contains("today_orders")).findFirst().orElseThrow();
        assertTrue(detailsSql.contains("'SALES'"));
        assertTrue(detailsSql.contains("'PROCESSING'"));
        assertTrue(detailsSql.contains("o.create_time>=:todayStart") && detailsSql.contains("o.create_time<:tomorrow"));
        assertTrue(detailsSql.contains("w.create_time>=:todayStart") && detailsSql.contains("w.create_time<:tomorrow"));
        assertTrue(detailsSql.contains("o.status=1 or (o.status=0 and o.pay_amount>0)"));
        assertTrue(detailsSql.contains("w.status<>'WITHDRAWN'"));
        assertTrue(detailsSql.contains("m.deleted=0") && detailsSql.contains("wm.deleted=0"));
    }

    private MockHttpServletRequest managerRequest(Set<String> permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("storeId", 1L);
        Claims claims = mock(Claims.class);
        when(claims.get("role")).thenReturn("MANAGER");
        request.setAttribute("claims", claims);
        request.setAttribute("permissions", permissions);
        return request;
    }
}
