package com.dajin.system.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionServiceTests {
    @Test
    void configuredUserUsesOnlyPersonalPermissions() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(contains("permission_initialized"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(contains("sys_user_permission"), anyMap(), eq(String.class)))
                .thenReturn(List.of("member:view", "report:view"));

        PermissionService service = new PermissionService(jdbc);

        assertEquals(Set.of("member:view", "report:view"), service.userPermissions(8L, 3L, "SALES"));
    }

    @Test
    void configuredUserMayHaveNoPermissions() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(contains("permission_initialized"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(contains("sys_user_permission"), anyMap(), eq(String.class))).thenReturn(List.of());

        PermissionService service = new PermissionService(jdbc);

        assertEquals(Set.of(), service.userPermissions(8L, 3L, "SALES"));
    }

    @Test
    void unconfiguredLegacyUserFallsBackToRoleTemplate() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(contains("permission_initialized"), anyMap(), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForList(contains("sys_role_permission"), anyMap(), eq(String.class)))
                .thenReturn(List.of("stock:view"));

        PermissionService service = new PermissionService(jdbc);

        assertEquals(Set.of("stock:view"), service.userPermissions(8L, 3L, "SALES"));
    }
}
