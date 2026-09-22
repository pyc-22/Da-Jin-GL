package com.dajin.system.processing;

import com.dajin.system.config.*;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import javax.servlet.http.HttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingCashierAccessTests {
    @Test void existingCashierWithCheckoutPermissionCanReadProcessingOrderAfterStartingIt() throws Exception {
        var jwt = mock(JwtService.class);
        var permissions = mock(PermissionService.class);
        when(jwt.parse("test")).thenReturn(Jwts.claims(java.util.Map.of("storeId", 1L, "role", "CASHIER")).setSubject("1"));
        when(permissions.userAccess(1L,1L)).thenReturn(new PermissionService.UserAccess(true,"CASHIER"));
        when(permissions.hasPermission(eq(1L),eq(1L),eq("CASHIER"),anyString())).thenAnswer(call -> "order:checkout".equals(call.getArgument(3)));
        var request = new MockHttpServletRequest("GET", "/api/processing/orders/1");
        request.addHeader("Authorization", "Bearer test");
        var method = new HandlerMethod(mock(ProcessingController.class), ProcessingController.class.getMethod("detail", long.class, HttpServletRequest.class));
        assertTrue(new AuthInterceptor(jwt, permissions).preHandle(request,new MockHttpServletResponse(),method));
    }
}
