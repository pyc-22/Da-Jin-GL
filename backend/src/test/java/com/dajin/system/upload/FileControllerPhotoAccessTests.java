package com.dajin.system.upload;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authorization matrix for private processing photo URLs. */
class FileControllerPhotoAccessTests {
    private static final Method CAN_READ;
    static {
        try {
            CAN_READ = FileController.class.getDeclaredMethod("canReadProcessingPhoto", String.class, javax.servlet.http.HttpServletRequest.class);
            CAN_READ.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void unauthenticatedPhotoRequestIsRejected() throws Exception {
        DbSupport db = mock(DbSupport.class);
        JwtService jwt = mock(JwtService.class);
        FileController controller = controller(db, jwt);

        assertFalse(canRead(controller, "processing/JG-1/pickup/a.jpg", new MockHttpServletRequest()));
        verifyNoInteractions(jwt, db);
    }

    @Test
    void salesCanReadOnlyOrdersTheyCreatedOrOwn() throws Exception {
        DbSupport db = mock(DbSupport.class);
        JwtService jwt = mock(JwtService.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        Claims claims = claims(12L, 3L, "SALES");
        when(jwt.parse("sales-token")).thenReturn(claims);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1, 0);
        FileController controller = controller(db, jwt);

        assertTrue(canRead(controller, "processing/JG-1/incoming/a.jpg", bearer("sales-token")));
        assertFalse(canRead(controller, "processing/JG-2/weigh/b.jpg", bearer("sales-token")));
        verify(jdbc, atLeastOnce()).queryForObject(contains("created_by=:uid or sales_id=:uid"), any(SqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void cashierCanReadAnyPhotoInTheirStore() throws Exception {
        DbSupport db = mock(DbSupport.class);
        JwtService jwt = mock(JwtService.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        Claims cashierClaims = claims(21L, 3L, "CASHIER");
        when(jwt.parse("cashier-token")).thenReturn(cashierClaims);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(1);
        FileController controller = controller(db, jwt);

        assertTrue(canRead(controller, "processing/JG-9/pickup/c.jpg", bearer("cashier-token")));
        verify(jdbc).queryForObject(contains("store_id=:store and order_no=:no and 1=1"), any(SqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void craftsmanCanReadOnlyAssignedOrders() throws Exception {
        DbSupport db = mock(DbSupport.class);
        JwtService jwt = mock(JwtService.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        Claims craftsmanClaims = claims(31L, 3L, "CRAFTSMAN");
        when(jwt.parse("craft-token")).thenReturn(craftsmanClaims);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(1);
        FileController controller = controller(db, jwt);

        assertTrue(canRead(controller, "processing/JG-4/weigh/d.jpg", bearer("craft-token")));
        verify(jdbc).queryForObject(contains("craftsman_id=:uid"), any(SqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void legacyAccessTokenQueryParameterRemainsReadable() throws Exception {
        DbSupport db = mock(DbSupport.class);
        JwtService jwt = mock(JwtService.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        Claims legacyClaims = claims(12L, 3L, "SALES");
        when(jwt.parse("legacy-token")).thenReturn(legacyClaims);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("access_token", "legacy-token");

        assertTrue(canRead(controller(db, jwt), "processing/JG-legacy/incoming/a.jpg", request));
        verify(jwt).parse("legacy-token");
    }

    private FileController controller(DbSupport db, JwtService jwt) {
        return new FileController("http://127.0.0.1:9000", "access", "secret", "test", db, jwt);
    }

    private boolean canRead(FileController controller, String object, MockHttpServletRequest request) throws Exception {
        return (Boolean) CAN_READ.invoke(controller, object, request);
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private Claims claims(long userId, long storeId, String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        when(claims.get("storeId")).thenReturn(storeId);
        when(claims.get("role")).thenReturn(role);
        return claims;
    }
}
