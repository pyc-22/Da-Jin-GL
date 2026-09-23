package com.dajin.system;

import com.dajin.system.config.JwtService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTests {
    @Test void tokenCarriesRoleAndStoreScope() {
        JwtService jwt = new JwtService("unit-test-secret-that-is-at-least-32-bytes", 2);
        var claims = jwt.parse(jwt.issue(7L, "admin", "ADMIN", 3L));
        assertEquals("7", claims.getSubject());
        assertEquals("ADMIN", claims.get("role"));
        assertEquals(3, ((Number) claims.get("storeId")).intValue());
    }
}
