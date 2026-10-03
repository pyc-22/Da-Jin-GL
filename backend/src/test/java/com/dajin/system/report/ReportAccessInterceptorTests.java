package com.dajin.system.report;

import com.dajin.system.admin.AdminController;
import com.dajin.system.admin.AdminFinanceController;
import com.dajin.system.common.DbSupport;
import com.dajin.system.common.GlobalExceptionHandler;
import com.dajin.system.config.*;
import com.dajin.system.processing.ProcessingController;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportAccessInterceptorTests {
    private final DbSupport db = mock(DbSupport.class);
    private Set<String> granted;
    private MockMvc mvc;

    @BeforeEach void setup() {
        JwtService jwt = mock(JwtService.class);
        PermissionService permissions = mock(PermissionService.class);
        var claims = Jwts.claims().setSubject("8");
        claims.put("storeId", 3L);
        claims.put("role", "MANAGER");
        when(jwt.parse("token")).thenReturn(claims);
        when(permissions.userAccess(8L, 3L)).thenReturn(new PermissionService.UserAccess(true, "MANAGER"));
        when(permissions.hasPermission(eq(8L), eq(3L), eq("MANAGER"), anyString()))
                .thenAnswer(i -> granted.contains("*") || granted.contains(i.getArgument(3)));
        when(permissions.userPermissions(8L, 3L, "MANAGER")).thenAnswer(i -> granted);
        when(db.store(any())).thenReturn(3L);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenAnswer(i -> new LinkedHashMap<>());
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        mvc = MockMvcBuilders.standaloneSetup(new ReportController(db), new AdminFinanceController(db),
                        new AdminController(db, null, null), new ProcessingController(db, null, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(jwt, permissions)).build();
    }

    @Test void independentDailyAccessDoesNotRequireStorePerformanceOrLegacyAll() throws Exception {
        granted = Set.of("report:view", "report:daily");
        mvc.perform(get("/api/report/daily").header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/admin/finance/summary").header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        clearInvocations(db);
        mvc.perform(get("/api/report/overview").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(403431));
        verifyNoInteractions(db);
    }

    @Test void monthlyEndpointsStayBlockedEvenWithLegacyWildcardPrerequisite() throws Exception {
        granted = Set.of("report:view", "report:view:all", "report:daily", "commission:manage");
        mvc.perform(get("/api/report/monthly").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(403431));
        mvc.perform(get("/api/admin/finance/summary").param("reportType", "monthly").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(403431));
        verifyNoInteractions(db);
    }

    @Test void monthlyFinanceIsIndependentOfDailyAndRejectsUnknownTypes() throws Exception {
        granted = Set.of("report:view", "report:monthly");
        mvc.perform(get("/api/report/monthly").param("month", "2026-09").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/admin/finance/summary").param("reportType", "monthly").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(200));
        clearInvocations(db);
        mvc.perform(get("/api/admin/finance/summary").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(403431));
        mvc.perform(get("/api/admin/finance/summary").param("reportType", "other").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(400431));
        verifyNoInteractions(db);
    }

    @Test void commissionAndProcessingStatisticsDoNotRequireOperationalPermissions() throws Exception {
        granted = Set.of("report:view", "report:commission", "report:processing");
        mvc.perform(get("/api/admin/commission/records").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/processing/statistics").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(200));
        granted = Set.of("report:view", "processing:view", "processing:manage", "commission:manage");
        clearInvocations(db);
        mvc.perform(get("/api/admin/commission/records").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/processing/statistics").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(db);
    }

    @Test void monthlyPermissionDoesNotControlPerformanceMonthFilters() throws Exception {
        granted = Set.of("report:view", "report:store-performance");
        mvc.perform(get("/api/report/overview").param("timeType", "lastMonth").header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.code").value(200));
        verify(db).one(contains("sales_amount"), argThat((SqlParameterSource p) ->
                p.getValue("startDate") != null && p.getValue("endDate") != null && Long.valueOf(3).equals(p.getValue("s"))));
    }
}
