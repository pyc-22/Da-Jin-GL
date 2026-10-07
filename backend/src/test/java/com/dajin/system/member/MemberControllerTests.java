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

    @Test
    void updateCanClearSalesAssignmentWithExplicitNull() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class))).thenReturn(1);
        MemberController controller = new MemberController(db, ws);

        controller.update(12L, new MemberController.Req("会员", "13800138002", "[]", null, null, null, null), request);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("sales_id=:sid"));
        assertEquals(null, parameters.getValue().getValue("sid"));
    }

    @Test
    void deleteSoftDeletesMemberAndKeepsHistory() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class)))
                .thenReturn(java.util.List.of(new java.util.HashMap<>(Map.of("member_id", 12L, "name", "会员", "balance", 0, "points", 0, "deleted", 0))));

        new MemberController(db, ws).remove(12L, request);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("deleted=1"), sql.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().toLowerCase().contains("delete from"), sql.getValue());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(ws).broadcast(eq("MEMBER_UPDATED"), event.capture());
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) event.getValue();
        assertEquals("DELETE", payload.get("action"));
    }

    @Test
    void deleteRefusesMemberWithStoredBalance() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class)))
                .thenReturn(java.util.List.of(new java.util.HashMap<>(Map.of("member_id", 12L, "name", "会员", "balance", new java.math.BigDecimal("168.50"), "points", 0, "deleted", 0))));

        var error = org.junit.jupiter.api.Assertions.assertThrows(com.dajin.system.common.BusinessException.class,
                () -> new MemberController(db, ws).remove(12L, request));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("储值余额"), error.getMessage());
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class));
    }

    @Test
    void listHidesDeletedMembers() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(request.getAttribute("claims")).thenReturn(claims);
        when(claims.get("role")).thenReturn("ADMIN");
        when(claims.getSubject()).thenReturn("1");
        when(db.list(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class))).thenReturn(java.util.List.of());
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);

        new MemberController(db, ws).list(1, 20, null, null, null, null, "desc", request);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, org.mockito.Mockito.atLeastOnce()).list(sql.capture(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getAllValues().get(0).contains("deleted=0"), sql.getAllValues().get(0));
    }

    @Test
    void createReusesArchivedProfileInsteadOfInsertingDuplicatePhone() {
        DbSupport db = mock(DbSupport.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(db.store(request)).thenReturn(3L);
        when(db.jdbc()).thenReturn(jdbc);
        when(request.getAttribute("claims")).thenReturn(claims);
        when(claims.get("role")).thenReturn("ADMIN");
        when(db.list(anyString(), org.mockito.ArgumentMatchers.any(MapSqlParameterSource.class)))
                .thenReturn(java.util.List.of(new java.util.HashMap<>(Map.of("member_id", 7L, "deleted", 1))));

        new MemberController(db, ws).create(new MemberController.Req("老会员", "13800138009", "[]", null, null, null, "前台快速登记"), request);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("deleted=0"), sql.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().toLowerCase().contains("insert into"), sql.getValue());
        assertEquals(7L, parameters.getValue().getValue("id"));
    }
}
