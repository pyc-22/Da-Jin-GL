package com.dajin.system.recycle;

import com.dajin.system.common.DbSupport;
import com.dajin.system.report.ReportController;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecycleSalesScopeTests {
    @Test void salesListIsScopedToCreatorNotPaymentApprover() {
        var request = request("SALES");
        DbSupport db = mock(DbSupport.class);
        when(db.store(request)).thenReturn(3L);
        new RecycleController(db, null, null, null).list(request);
        verify(db).list(contains("created_by=:uid"), argThat((Map<String,?> p) -> Long.valueOf(8).equals(p.get("uid")) && Long.valueOf(3).equals(p.get("s"))));
    }

    @Test void recycleReportUsesCreatorForSummaryTrendAndRecords() {
        var request = request("SALES");
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(new LinkedHashMap<>());
        when(db.list(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        new ReportController(db).recycle(null, null, request);
        verify(db).one(contains("ro.created_by=:uid"), any(SqlParameterSource.class));
        verify(db, times(2)).list(contains("ro.created_by=:uid"), any(SqlParameterSource.class));
        verify(db, never()).one(contains("fr.operator_id"), any(SqlParameterSource.class));
    }

    @Test void managerStillListsTheirEntireStore() {
        var request = request("MANAGER");
        DbSupport db = mock(DbSupport.class);
        when(db.store(request)).thenReturn(3L);
        new RecycleController(db, null, null, null).list(request);
        verify(db).list(argThat((String sql) -> sql.contains("store_id=:s") && !sql.contains("created_by=:uid")), anyMap());
    }

    private MockHttpServletRequest request(String role) {
        var r = new MockHttpServletRequest();
        r.setAttribute("claims", Jwts.claims(Map.of("role", role)).setSubject("8"));
        return r;
    }
}
