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
        assertTrue(all.contains("date(o.create_time)>=:from"), all);
        var params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(db, atLeastOnce()).one(anyString(), params.capture());
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")), params.getValue().getValue("today"));
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
