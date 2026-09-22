package com.dajin.system.member;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import io.jsonwebtoken.Claims;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberControllerTests {
    @Test
    void broadcastsStoreScopedRefreshAfterMemberCreation() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(9L);
        when(db.jdbc()).thenReturn(jdbc);
        Claims claims = mock(Claims.class);
        when(claims.get("role")).thenReturn("ADMIN");
        when(request.getAttribute("claims")).thenReturn(claims);
        MemberController controller = new MemberController(db, ws);

        controller.create(new MemberController.Req("新会员", "13800138000", "[]", null, null, null, "前台快速登记"), request);

        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(ws).broadcast(eq("MEMBER_UPDATED"), event.capture());
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) event.getValue();
        assertEquals(9L, payload.get("storeId"));
        assertEquals("CREATE", payload.get("action"));
    }

    @Test
    void assignsNewMemberToCurrentSalesUser() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(request.getAttribute("claims")).thenReturn(claims);
        when(claims.get("role")).thenReturn("SALES");
        when(claims.getSubject()).thenReturn("27");
        MemberController controller = new MemberController(db, ws);

        controller.create(new MemberController.Req("销售新增会员", "13800138001", "[]", null, null, null, "移动端"), request);

        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertEquals(27L, parameters.getValue().getValue("sid"));
    }
}
