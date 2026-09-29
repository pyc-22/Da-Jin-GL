package com.dajin.system.admin;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.PermissionService;
import com.dajin.system.config.SyncWebSocketHandler;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerRolePermissionTests {
    @Test void administratorCanSaveManagerRoleWithRequiredReadPermissions() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(3L);
        when(jdbc.queryForObject(contains("from sys_role"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(request.getAttribute("claims")).thenReturn(Jwts.claims().setSubject("1"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        var response = new RolePermissionController(db, mock(PermissionService.class), ws)
                .save("MANAGER", Map.of("permissions", List.of("gold:manage")), request);

        assertTrue(((Map<?, ?>) response.data()).get("permissions") instanceof java.util.Set<?>);
        verify(jdbc).update(contains("permission_initialized=1"), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
        verify(jdbc).update(contains("sys_role_permission(store_id,role_code,permission_code)"), eq(Map.of("store", 3L, "role", "MANAGER", "permission", "gold:view")));
        verify(ws).broadcast(eq("ROLE_PERMISSIONS_UPDATED"), anyMap());
    }
}
