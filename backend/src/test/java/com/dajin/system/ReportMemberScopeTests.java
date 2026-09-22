package com.dajin.system;

import com.dajin.system.common.DbSupport;
import com.dajin.system.report.ReportController;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportMemberScopeTests {
    @Test
    void salesMemberReportUsesTheAliasOfEachMemberSubquery() {
        DbSupport db = mock(DbSupport.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);

        when(request.getAttribute("claims")).thenReturn(claims);
        when(claims.get("role")).thenReturn("SALES");
        when(claims.getSubject()).thenReturn("3");
        when(db.store(request)).thenReturn(1L);
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        when(db.one(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("from member m where m.store_id=:s and m.sales_id=:uid"));
            assertTrue(sql.contains("from member mx where mx.store_id=:s and mx.sales_id=:uid"));
            assertTrue(sql.contains("from member mb where mb.store_id=:s and mb.sales_id=:uid"));
            assertTrue(sql.contains("from member ma where ma.store_id=:s and ma.sales_id=:uid"));
            assertFalse(sql.contains("from member mb where mb.store_id=:s and mx.sales_id=:uid"));
            assertFalse(sql.contains("from member ma where ma.store_id=:s and mx.sales_id=:uid"));
            return new LinkedHashMap<>();
        });

        new ReportController(db).member("custom", "2026-09-18", "2026-09-19", request);
    }
}
