package com.dajin.system.auth;

import com.dajin.system.common.BusinessException;
import com.dajin.system.config.JwtService;
import com.dajin.system.config.PermissionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordByteLengthTests {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AuthController controller = new AuthController(jdbc, mock(JwtService.class), mock(PermissionService.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void multibytePasswordOver72Utf8BytesIsRejected() {
        String password = "A1" + "你".repeat(24); // 74 UTF-8 bytes, 26 Java characters
        assertTrue(password.length() <= 72);
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > 72);
        BusinessException error = assertThrows(BusinessException.class, () -> controller.changePassword(
                new AuthController.ChangePasswordReq("Old1pass", password), request));
        assertEquals(400427, error.getCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void passwordExactly72Utf8BytesIsAccepted() {
        String password = "A1" + "你".repeat(23) + "x"; // 72 UTF-8 bytes
        assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);
        Claims claims = Jwts.claims();
        claims.setSubject("1");
        claims.put("storeId", 1L);
        request.setAttribute("claims", claims);
        String stored = new BCryptPasswordEncoder().encode("Old1pass");
        when(jdbc.queryForMap(anyString(), anyMap())).thenReturn(Map.of("password", stored));
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        assertEquals(200, controller.changePassword(
                new AuthController.ChangePasswordReq("Old1pass", password), request).code());
        verify(jdbc).update(contains("update sys_user set password"), anyMap());
    }
}
