package com.dajin.system.auth;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.config.JwtService;
import com.dajin.system.config.PermissionService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTests {
    @Test
    void currentUserReturnsLatestPermissionsWithoutPassword() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JwtService jwt = mock(JwtService.class);
        PermissionService permissions = mock(PermissionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(request.getAttribute("claims")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("8");
        when(claims.get("storeId")).thenReturn(3L);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.ofEntries(
                Map.entry("user_id", 8L), Map.entry("username", "sales8"), Map.entry("password", "secret"),
                Map.entry("real_name", "销售八"), Map.entry("role_id", 4L), Map.entry("store_id", 3L),
                Map.entry("status", 1), Map.entry("role_code", "SALES"), Map.entry("role_name", "销售"),
                Map.entry("store_name", "测试店"))));
        when(permissions.userPermissions(8L, 3L, "SALES")).thenReturn(Set.of("stock:view"));

        AuthController controller = new AuthController(jdbc, jwt, permissions);
        ApiResponse<?> response = controller.currentUser(request);

        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) response.data();
        @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>) body.get("user");
        assertFalse(user.containsKey("password"));
        assertEquals(List.of("stock:view"), body.get("permissions"));
    }
}
