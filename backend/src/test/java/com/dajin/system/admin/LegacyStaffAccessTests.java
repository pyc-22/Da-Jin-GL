package com.dajin.system.admin;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyStaffAccessTests {
    @Test void managerCannotCreateAnAdministratorThroughLegacyRoute() {
        DbSupport db = mock(DbSupport.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var request = new MockHttpServletRequest();
        request.setAttribute("claims", Jwts.claims(Map.of("role", "MANAGER")).setSubject("2"));
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
        when(db.one(contains("sys_role"), anyMap())).thenReturn(Map.of("role_code", "ADMIN"));
        var controller = new AdminController(db, mock(SyncWebSocketHandler.class), mock(BackupService.class));
        assertThrows(BusinessException.class, () -> controller.createUser(Map.of("username", "admin2", "realName", "Test", "roleId", 1), request));
        verify(jdbc, never()).update(contains("insert into sys_user"), any(SqlParameterSource.class));
    }
}
