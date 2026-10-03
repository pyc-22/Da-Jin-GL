package com.dajin.system.report;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.PermissionCatalog;
import com.dajin.system.config.ReportAccess;
import com.dajin.system.admin.AdminController;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportPermissionsTests {
    @Test void managerReportDefaultsAreIndependent() {
        Set<String> defaults = PermissionCatalog.defaults("MANAGER");
        assertFalse(defaults.contains("report:store-performance"));
        assertFalse(defaults.contains("report:monthly"));
        assertTrue(defaults.containsAll(Set.of("report:commission", "report:daily", "report:processing", "report:recycle")));
        assertTrue(PermissionCatalog.withPrerequisites(Set.of("report:daily")).contains("report:view"));
        assertFalse(PermissionCatalog.withPrerequisites(Set.of("commission:manage")).contains("report:store-performance"));
    }

    @Test void disabledManagerPerformanceStopsBeforeDatabaseReads() {
        DbSupport db = mock(DbSupport.class);
        var request = manager(Set.of("report:view", "report:daily"));
        assertThrows(BusinessException.class, () -> new ReportController(db).performance(null, null, request));
        verifyNoInteractions(db);
    }

    @Test void performanceDoesNotReturnDisabledCommissionAndProcessingFields() {
        DbSupport db = mock(DbSupport.class);
        var request = manager(Set.of("report:view", "report:store-performance"));
        when(db.store(request)).thenReturn(1L);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(Map.of("amount", 100));
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        Map<?,?> result = (Map<?,?>) new ReportController(db).performance(null, "2026-10", request).data();
        assertFalse(result.containsKey("commission"));
        assertFalse(result.containsKey("processing_base"));
    }

    @Test void dashboardHidesSalesQueriesWhenPerformanceIsOff() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        var request = manager(Set.of("dashboard:view", "report:daily"));
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(2);
        Map<?,?> result = (Map<?,?>) new AdminController(db, null, null).dashboard(request).data();
        assertEquals(2, result.get("pendingApproval"));
        assertFalse(result.containsKey("revenue"));
        assertFalse(result.containsKey("ranking"));
        assertFalse(result.containsKey("trend"));
        verify(db, never()).one(anyString(), any(SqlParameterSource.class));
        verify(db, never()).list(anyString(), any(SqlParameterSource.class));
        verify(jdbc, never()).queryForObject(contains("finance_record"), any(SqlParameterSource.class), any(Class.class));
    }

    @Test void nestedFilteringRemovesDisabledFieldsWithoutChangingSourceAndIsIdempotent() {
        var request = manager(Set.of("report:daily"));
        Map<String,Object> source = new LinkedHashMap<>(Map.of("summary", Map.of("turnover", 130, "sales_amount", 100, "processing_amount", 30, "commission", 3),
                "rows", List.of(Map.of("amount", 130, "turnover", 130, "sales_amount", 100, "processing_amount", 30, "recycle_amount", 50)),
                "employees", List.of(Map.of("order_count", 5, "sales_order_count", 2, "processing_orders", 3, "commission_amount", 7))));
        Map<?,?> result = (Map<?,?>) ReportAccess.filter(request, source);
        Map<?,?> summary = (Map<?,?>) result.get("summary");
        assertEquals("100", summary.get("turnover").toString());
        assertFalse(summary.containsKey("commission"));
        Map<?,?> row = (Map<?,?>) ((List<?>) result.get("rows")).get(0);
        assertEquals(100, row.get("amount"));
        assertFalse(row.containsKey("recycle_amount"));
        Map<?,?> employee = (Map<?,?>) ((List<?>) result.get("employees")).get(0);
        assertEquals(2, employee.get("order_count"));
        assertFalse(employee.containsKey("commission_amount"));
        assertEquals(result, ReportAccess.filter(request, result));
        assertTrue(((Map<?,?>) source.get("summary")).containsKey("processing_amount"));
    }

    @Test void salesCannotSelectAnotherEmployeesPerformance() {
        DbSupport db = mock(DbSupport.class);
        var request = manager(Set.of("report:view", "report:view:all", "*"));
        ((io.jsonwebtoken.Claims) request.getAttribute("claims")).put("role", "SALES");
        when(db.store(request)).thenReturn(3L);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(new LinkedHashMap<>(Map.of("amount", 100)));
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        ReportController controller = new ReportController(db);
        controller.performance(99L, "2026-10", request);
        verify(db).one(contains("o.sales_id=:selectedUid"), argThat((SqlParameterSource p) -> Long.valueOf(8).equals(p.getValue("selectedUid")) && Long.valueOf(3).equals(p.getValue("s"))));
        clearInvocations(db);
        controller.overview("lastMonth", null, null, 99L, null, request);
        verify(db).one(contains("o1.sales_id=:employeeId"), argThat((SqlParameterSource p) -> Long.valueOf(8).equals(p.getValue("employeeId"))));
    }

    private MockHttpServletRequest manager(Set<String> permissions) {
        var request = new MockHttpServletRequest();
        request.setAttribute("claims", Jwts.claims(Map.of("role", "MANAGER")).setSubject("8"));
        request.setAttribute("permissions", permissions);
        return request;
    }
}
